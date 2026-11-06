package com.idear.backend.blockchain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idear.backend.alert.infrastructure.repository.AlertRepository;
import com.idear.backend.blockchain.application.BlockchainOutboxProcessor;
import com.idear.backend.blockchain.application.BlockchainService;
import com.idear.backend.blockchain.domain.BlockchainOutbox;
import com.idear.backend.blockchain.dto.request.RegistrationResultRequest;
import com.idear.backend.blockchain.infrastructure.BlockchainGatewayClient;
import com.idear.backend.blockchain.infrastructure.BlockchainOutboxRepository;
import com.idear.backend.blockchain.infrastructure.OnChainCommit;
import com.idear.backend.config.EmbeddedRedisConfig;
import com.idear.backend.global.exception.CustomException;
import com.idear.backend.global.exception.ErrorCode;
import com.idear.backend.idea.application.FileStorageService;
import com.idear.backend.idea.application.IdeaService;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.dto.request.FileSignatureRequest;
import com.idear.backend.idea.dto.request.IdeaCreateRequest;
import com.idear.backend.idea.dto.response.FileHashInfo;
import com.idear.backend.idea.dto.response.IdeaProofResponse;
import com.idear.backend.idea.dto.response.IdeaRegistrationInitResponse;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;
import com.idear.backend.user.domain.User;
import com.idear.backend.user.domain.UserRole;
import com.idear.backend.user.infrastructure.repository.UserRepository;

/**
 * 등록 → 서명 → Outbox → 전송 → 웹훅(100회) → proof.json → 삭제(파기) 전체 흐름.
 * 외부 의존성(게이트웨이, S3)만 Mock 이고 DB·트랜잭션·이벤트 리스너는 실제로 동작한다.
 *
 * <p>부산물로 build/proof-sample/ 에 파일과 proof.json 을 남겨, 독립 검증 도구(tools/proof-verifier)가
 * Java 와 같은 결과를 내는지 Node 로 확인할 수 있게 한다.
 */
@SpringBootTest
@Import(EmbeddedRedisConfig.class)
class BlockchainRegistrationFlowIntegrationTest {

	private static final ObjectMapper OM = new ObjectMapper();

	@MockitoBean BlockchainGatewayClient gateway;
	@MockitoBean FileStorageService fileStorageService;

	@Autowired IdeaService ideaService;
	@Autowired BlockchainService blockchainService;
	@Autowired BlockchainOutboxProcessor outboxProcessor;
	@Autowired BlockchainOutboxRepository outboxRepository;
	@Autowired IdeaFileRepository ideaFileRepository;
	@Autowired UserRepository userRepository;
	@Autowired AlertRepository alertRepository;

	private KeyPair userKey;
	private User user;

	@BeforeEach
	void setUp() throws Exception {
		when(fileStorageService.uploadFile(any(), anyString(), anyString())).thenAnswer(inv -> "s3://" + inv.getArgument(1));
		userKey = CryptoTestSupport.newP256KeyPair();
		User u = User.of("tester", "tester@example.com", "kakao_" + System.nanoTime(), UserRole.USER);
		u.registerPublicKey(CryptoTestSupport.spkiBase64(userKey));
		u.updateNotificationSettings(true, false);
		user = userRepository.save(u);
	}

	@Test
	@DisplayName("전체 흐름: 서명 검증 → Outbox → 전송 → 웹훅 100회(멱등) → proof.json → 파기")
	void fullFlow() throws Exception {
		byte[] content = "my secret idea document".getBytes();
		IdeaRegistrationInitResponse init = ideaService.initIdeaRegistration(user, createRequest(), null,
				List.of(new MockMultipartFile("files", "idea.txt", "text/plain", content)));
		FileHashInfo hashInfo = init.getFiles().getFirst();

		// 브라우저와 같은 방식으로 서명
		String message = hashInfo.getFileHash() + "|" + hashInfo.getTimestamp();
		String userSignature = CryptoTestSupport.webCryptoSign(userKey, message);
		ideaService.submitSignatures(user, init.getIdeaId(), signatureRequest(hashInfo.getIdeaFileId(), userSignature));

		// 1) 서명 제출 시 게이트웨이를 직접 호출하지 않고 Outbox 에만 기록된다
		verify(gateway, never()).requestRegistration(any(), any(), any(), any());
		IdeaFile file = ideaFileRepository.findById(hashInfo.getIdeaFileId()).orElseThrow();
		BlockchainOutbox outbox = outboxRepository.findByCommit(file.getCommit()).orElseThrow();
		assertThat(outbox.getStatus()).isEqualTo(BlockchainOutbox.Status.PENDING);
		assertThat(file.getRegisterStatus()).isEqualTo(IdeaFile.RegisterStatus.BLOCKCHAIN_PENDING);

		// 2) 릴레이 전송: 체인에는 서명 다이제스트가 간다
		assertThat(outboxProcessor.dispatch(outbox.getOutboxId())).isEqualTo(BlockchainOutboxProcessor.Result.SENT);
		verify(gateway).requestRegistration(eq(file.getCommit()), eq(file.getRequestedTimestamp()),
				eq(file.getOnChainUserSignature()), eq(file.getServerSignature()));
		assertThat(file.getOnChainUserSignature()).isNotEqualTo(userSignature);

		// 3) 같은 success 웹훅 100회 → 상태 전이 1회, 알림 1건
		when(gateway.findCommit(file.getCommit())).thenReturn(Optional.of(new OnChainCommit(
				file.getCommit(), "0x" + "7".repeat(64), 4242, 1_731_000_000L, file.getRequestedTimestamp(),
				file.getOnChainUserSignature(), file.getServerSignature())));
		long alertsBefore = alertRepository.count();
		RegistrationResultRequest webhook = OM.readValue("""
				{"status":"success","commit":"%s","txHash":"0xwhatever","successData":{"blockNumber":1,"registeredAt":1}}
				""".formatted(file.getCommit()), RegistrationResultRequest.class);
		for (int i = 0; i < 100; i++) {
			blockchainService.handleRegistrationResult(webhook);
		}
		IdeaFile registered = ideaFileRepository.findById(file.getIdeaFileId()).orElseThrow();
		assertThat(registered.getRegisterStatus()).isEqualTo(IdeaFile.RegisterStatus.REGISTERED);
		assertThat(registered.getTxHash()).isEqualTo("0x" + "7".repeat(64));
		assertThat(alertRepository.count() - alertsBefore).isEqualTo(1);

		// 4) proof.json: 독립 검증 도구용 샘플로 저장
		IdeaProofResponse proof = ideaService.getProof(user, init.getIdeaId(), file.getIdeaFileId());
		assertThat(proof.getSalt()).isEqualTo(registered.getSalt());
		assertThat(proof.getUserSignature()).isEqualTo(userSignature);
		writeProofSample(content, proof);

		// 5) 삭제 → 파기: S3 원본 삭제 + salt/해시/서명 제거
		ideaService.deleteIdea(user, init.getIdeaId());
		verify(fileStorageService).deleteFile(registered.getFileName(), "file");
		assertThat(ideaFileRepository.findById(file.getIdeaFileId())).isEmpty(); // soft-deleted → 조회 제외
		// 파기 후 들어온 웹훅은 무시된다
		blockchainService.handleRegistrationResult(webhook);
	}

	@Test
	@DisplayName("잘못된 사용자 서명은 거부되고 Outbox 에 아무것도 남지 않는다")
	void rejectInvalidSignature() throws Exception {
		IdeaRegistrationInitResponse init = ideaService.initIdeaRegistration(user, createRequest(), null,
				List.of(new MockMultipartFile("files", "a.txt", "text/plain", "other".getBytes())));
		FileHashInfo hashInfo = init.getFiles().getFirst();
		KeyPair attacker = CryptoTestSupport.newP256KeyPair();
		String forged = CryptoTestSupport.webCryptoSign(attacker, hashInfo.getFileHash() + "|" + hashInfo.getTimestamp());

		assertThatThrownBy(() -> ideaService.submitSignatures(user, init.getIdeaId(),
				signatureRequest(hashInfo.getIdeaFileId(), forged)))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_USER_SIGNATURE);

		IdeaFile file = ideaFileRepository.findById(hashInfo.getIdeaFileId()).orElseThrow();
		assertThat(file.getRegisterStatus()).isEqualTo(IdeaFile.RegisterStatus.PENDING_USER_SIGNATURE);
		assertThat(outboxRepository.findByCommit(file.getCommit())).isEmpty();
	}

	private static IdeaCreateRequest createRequest() throws Exception {
		return OM.readValue("""
				{"title":"t","shortDescription":"s","description":"d"}
				""", IdeaCreateRequest.class);
	}

	private static FileSignatureRequest signatureRequest(Long ideaFileId, String sig) throws Exception {
		return OM.readValue("""
				{"signatures":[{"ideaFileId":%d,"userSignature":"%s"}]}
				""".formatted(ideaFileId, sig), FileSignatureRequest.class);
	}

	private static void writeProofSample(byte[] content, IdeaProofResponse proof) throws Exception {
		Path dir = Path.of("build", "proof-sample");
		Files.createDirectories(dir);
		Files.write(dir.resolve("idea.txt"), content);
		ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		Files.writeString(dir.resolve("proof.json"), pretty.writeValueAsString(proof));
		// 온체인 조회 대신 쓸 모의 체인 레코드
		Files.writeString(dir.resolve("chain-record.json"), pretty.writeValueAsString(Map.of(
				"exists", true,
				"timestamp", proof.getRequestedTimestamp(),
				"userSignature", proof.getOnChainUserSignature(),
				"serverSignature", proof.getServerSignature(),
				"blockNumber", proof.getBlockNumber(),
				"registeredAt", proof.getRegisteredAt())));
	}
}
