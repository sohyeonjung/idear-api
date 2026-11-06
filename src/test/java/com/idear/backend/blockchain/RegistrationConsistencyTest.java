package com.idear.backend.blockchain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idear.backend.blockchain.application.BlockchainOutboxProcessor;
import com.idear.backend.blockchain.application.BlockchainOutboxService;
import com.idear.backend.blockchain.application.BlockchainReconciler;
import com.idear.backend.blockchain.application.BlockchainService;
import com.idear.backend.blockchain.application.RegistrationResultEvent;
import com.idear.backend.blockchain.application.RegistrationStateService;
import com.idear.backend.blockchain.application.RegistrationStateService.Outcome;
import com.idear.backend.blockchain.domain.BlockchainOutbox;
import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.blockchain.dto.request.RegistrationResultRequest;
import com.idear.backend.blockchain.infrastructure.BlockchainGatewayClient;
import com.idear.backend.blockchain.infrastructure.BlockchainOutboxRepository;
import com.idear.backend.blockchain.infrastructure.GatewayUnavailableException;
import com.idear.backend.blockchain.infrastructure.OnChainCommit;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.domain.IdeaFile.RegisterStatus;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;

/**
 * 온체인·오프체인 정합성 시나리오 테스트.
 * 게이트웨이(idear-chain)는 별도 저장소라 Mock 으로 대체하고, "체인 조회 결과"를 시나리오별로 바꾼다.
 */
class RegistrationConsistencyTest {

	private static final String COMMIT = "0x" + "c".repeat(64);
	private static final String USER_SIG = "0x" + "1".repeat(128);
	private static final String ONCHAIN_SIG = "0x" + "d".repeat(64);
	private static final ObjectMapper OM = new ObjectMapper();

	private IdeaFileRepository ideaFileRepository;
	private BlockchainGatewayClient gateway;
	private ApplicationEventPublisher publisher;
	private RegistrationStateService stateService;
	private IdeaFile file;

	@BeforeEach
	void setUp() {
		ideaFileRepository = mock(IdeaFileRepository.class);
		gateway = mock(BlockchainGatewayClient.class);
		publisher = mock(ApplicationEventPublisher.class);
		stateService = new RegistrationStateService(publisher);

		file = IdeaFile.initialize("a.pdf", "uuid.pdf", "file/uuid.pdf", "a".repeat(64), "b".repeat(64), COMMIT, 1L);
		ReflectionTestUtils.setField(file, "ideaFileId", 1L);
		file.submitUserSignature(USER_SIG, "0xserver", "spki", ONCHAIN_SIG, 2);
		when(ideaFileRepository.findIdeaFileByCommit(COMMIT)).thenReturn(Optional.of(file));
		when(ideaFileRepository.findById(1L)).thenReturn(Optional.of(file));
	}

	private static OnChainCommit chainRecord() {
		return new OnChainCommit(COMMIT, "0xrealtx", 123, 1_700_000_000L, 1L, ONCHAIN_SIG, "0xserver");
	}

	private static RegistrationResultRequest webhook(String json) throws Exception {
		return OM.readValue(json, RegistrationResultRequest.class);
	}

	private static String success(String txHash) {
		return """
				{"status":"success","commit":"%s","txHash":"%s","successData":{"blockNumber":999,"registeredAt":1}}
				""".formatted(COMMIT, txHash);
	}

	private static String failure(String reason) {
		return """
				{"status":"failure","commit":"%s","failureData":{"reason":"%s","error":"x"}}
				""".formatted(COMMIT, reason);
	}

	@Nested
	@DisplayName("웹훅 처리: 본문을 믿지 않고 체인 상태로 확정")
	class Webhook {

		private BlockchainService service;

		@BeforeEach
		void init() {
			service = new BlockchainService(ideaFileRepository, gateway, stateService);
		}

		@Test
		@DisplayName("위조 success 웹훅(체인에 없음)은 상태를 바꾸지 않는다")
		void forgedSuccessIgnored() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.empty());

			Outcome outcome = service.handleRegistrationResult(webhook(success("0xfake")));

			assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.BLOCKCHAIN_PENDING);
			assertThat(file.getTxHash()).isNull();
			verifyNoInteractions(publisher);
		}

		@Test
		@DisplayName("success 웹훅이 와도 저장 값은 웹훅이 아닌 체인 값(txHash, blockNumber)을 쓴다")
		void usesChainValues() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(chainRecord()));

			service.handleRegistrationResult(webhook(success("0xwebhook-claims-this")));

			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.REGISTERED);
			assertThat(file.getTxHash()).isEqualTo("0xrealtx");
			assertThat(file.getBlockNumber()).isEqualTo(123);
			assertThat(file.getRegisteredTimestamp()).isEqualTo(1_700_000_000_000L);
		}

		@Test
		@DisplayName("failure(타임아웃) 웹훅이 와도 실제로 체인에 있으면 REGISTERED 로 확정한다")
		void failureButMined() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(chainRecord()));

			Outcome outcome = service.handleRegistrationResult(webhook(failure("network_error")));

			assertThat(outcome).isEqualTo(Outcome.CONFIRMED);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.REGISTERED);
		}

		@Test
		@DisplayName("체인에 없고 failure 면 FAILED, 같은 failure 가 또 와도 이벤트는 1번")
		void failureRecordedOnce() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.empty());

			assertThat(service.handleRegistrationResult(webhook(failure("rpc_rate_limit")))).isEqualTo(Outcome.FAILED);
			assertThat(service.handleRegistrationResult(webhook(failure("rpc_rate_limit")))).isEqualTo(Outcome.UNCHANGED);

			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.FAILED);
			assertThat(file.getRegistrationFailureReason()).isEqualTo(RegistrationFailureReason.RPC_RATE_LIMIT);
			verify(publisher, times(1)).publishEvent(any(RegistrationResultEvent.class));
		}

		@Test
		@DisplayName("동일 success 웹훅 100회 → 상태 전이 1회, 후처리 이벤트 1회 (멱등)")
		void duplicateWebhooksIdempotent() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(chainRecord()));

			int confirmed = 0;
			for (int i = 0; i < 100; i++) {
				if (service.handleRegistrationResult(webhook(success("0xrealtx"))) == Outcome.CONFIRMED) {
					confirmed++;
				}
			}
			assertThat(confirmed).isEqualTo(1);
			verify(publisher, times(1)).publishEvent(any(RegistrationResultEvent.class));
		}

		@Test
		@DisplayName("게이트웨이 장애로 체인 확인이 불가하면 상태를 바꾸지 않는다 (대사 배치가 처리)")
		void gatewayDownDefers() throws Exception {
			when(gateway.findCommit(COMMIT)).thenThrow(new GatewayUnavailableException("down", null));

			service.handleRegistrationResult(webhook(failure("network_error")));

			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.BLOCKCHAIN_PENDING);
		}

		@Test
		@DisplayName("체인 레코드의 사용자 서명 다이제스트가 DB 와 다르면 확정하지 않는다")
		void mismatchNotConfirmed() throws Exception {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(
					new OnChainCommit(COMMIT, "0xtx", 1, 1L, 1L, "0x" + "e".repeat(64), "0xserver")));

			assertThat(service.handleRegistrationResult(webhook(success("0xtx")))).isEqualTo(Outcome.ONCHAIN_MISMATCH);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.BLOCKCHAIN_PENDING);
		}

		@Test
		@DisplayName("모르는 commit 웹훅은 조용히 무시한다 (존재 여부 노출 방지)")
		void unknownCommit() throws Exception {
			when(ideaFileRepository.findIdeaFileByCommit(anyString())).thenReturn(Optional.empty());
			assertThat(service.handleRegistrationResult(webhook(success("0x1")))).isEqualTo(Outcome.UNCHANGED);
			verifyNoInteractions(gateway);
		}
	}

	@Nested
	@DisplayName("대사 배치: 웹훅이 유실돼도 체인과 비교해 복구")
	class Reconciliation {

		private BlockchainOutboxService outboxService;
		private BlockchainReconciler reconciler;

		@BeforeEach
		void init() {
			outboxService = mock(BlockchainOutboxService.class);
			reconciler = new BlockchainReconciler(ideaFileRepository, gateway, stateService, outboxService, publisher);
		}

		@Test
		@DisplayName("웹훅 유실: PENDING 인데 체인에 있으면 REGISTERED 로 복구")
		void recoverLostWebhook() {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(chainRecord()));

			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.RECOVERED);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.REGISTERED);
		}

		@Test
		@DisplayName("실패로 오판된 건: FAILED 인데 나중에 채굴됐으면 REGISTERED 로 정정")
		void recoverFalseFailure() {
			file.markFailed(null, RegistrationFailureReason.NETWORK_ERROR);
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.of(chainRecord()));

			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.RECOVERED);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.REGISTERED);
			assertThat(file.getRegistrationFailureReason()).isNull();
		}

		@Test
		@DisplayName("아직 유예 시간 안이면 아무것도 하지 않는다")
		void stillPending() {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.empty());
			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.STILL_PENDING);
			verifyNoInteractions(outboxService);
		}

		@Test
		@DisplayName("요청 유실: 오래 PENDING 인데 체인에 없으면 재전송, 최대 횟수 초과 시 FAILED")
		void redispatchThenTimeout() {
			when(gateway.findCommit(COMMIT)).thenReturn(Optional.empty());

			ReflectionTestUtils.setField(file, "statusChangedAt", LocalDateTime.now().minusHours(1));
			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.REDISPATCHED);
			assertThat(file.getDispatchCount()).isEqualTo(2);

			ReflectionTestUtils.setField(file, "statusChangedAt", LocalDateTime.now().minusHours(1));
			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.REDISPATCHED);

			ReflectionTestUtils.setField(file, "statusChangedAt", LocalDateTime.now().minusHours(1));
			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.TIMED_OUT);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.FAILED);
			assertThat(file.getRegistrationFailureReason()).isEqualTo(RegistrationFailureReason.RECONCILIATION_TIMEOUT);
			verify(outboxService, times(2)).enqueue(file);
		}

		@Test
		@DisplayName("게이트웨이 장애 중에는 판단을 미룬다 (없음과 장애를 구분)")
		void gatewayDown() {
			when(gateway.findCommit(COMMIT)).thenThrow(new GatewayUnavailableException("down", null));
			ReflectionTestUtils.setField(file, "statusChangedAt", LocalDateTime.now().minusHours(1));

			assertThat(reconciler.reconcile(1L)).isEqualTo(BlockchainReconciler.Result.GATEWAY_DOWN);
			assertThat(file.getDispatchCount()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("Outbox 릴레이: 전송 실패 시 백오프 재시도, 한도 초과 시 Dead Letter")
	class Outbox {

		private BlockchainOutboxRepository outboxRepository;
		private BlockchainOutboxProcessor processor;
		private BlockchainOutbox outbox;

		@BeforeEach
		void init() {
			outboxRepository = mock(BlockchainOutboxRepository.class);
			processor = new BlockchainOutboxProcessor(outboxRepository, ideaFileRepository, gateway, publisher);
			outbox = BlockchainOutbox.pending(1L, COMMIT);
			when(outboxRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(outbox));
		}

		@Test
		@DisplayName("전송 성공 시 SENT, 체인에는 사용자 서명 원문이 아닌 다이제스트를 보낸다")
		void sendsDigest() {
			assertThat(processor.dispatch(10L)).isEqualTo(BlockchainOutboxProcessor.Result.SENT);
			assertThat(outbox.getStatus()).isEqualTo(BlockchainOutbox.Status.SENT);
			verify(gateway).requestRegistration(COMMIT, 1L, ONCHAIN_SIG, "0xserver");
		}

		@Test
		@DisplayName("게이트웨이 장애가 계속되면 5회 후 DEAD, 파일은 FAILED(gateway_unreachable)")
		void deadLetter() {
			doThrow(new GatewayUnavailableException("connection refused", null))
					.when(gateway).requestRegistration(anyString(), anyLong(), anyString(), anyString());

			for (int i = 0; i < 4; i++) {
				assertThat(processor.dispatch(10L)).isEqualTo(BlockchainOutboxProcessor.Result.RETRY_SCHEDULED);
				assertThat(outbox.getNextAttemptAt()).isAfter(LocalDateTime.now());
			}
			assertThat(processor.dispatch(10L)).isEqualTo(BlockchainOutboxProcessor.Result.DEAD);
			assertThat(outbox.getStatus()).isEqualTo(BlockchainOutbox.Status.DEAD);
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.FAILED);
			assertThat(file.getRegistrationFailureReason()).isEqualTo(RegistrationFailureReason.GATEWAY_UNREACHABLE);
		}

		@Test
		@DisplayName("파일이 이미 확정/삭제됐으면 보내지 않는다")
		void skipWhenNotPending() {
			file.confirmOnChain("0xtx", 1, 1L);
			assertThat(processor.dispatch(10L)).isEqualTo(BlockchainOutboxProcessor.Result.SKIPPED);
			verifyNoInteractions(gateway);
		}
	}

	@Nested
	@DisplayName("IdeaFile 상태 전이")
	class StateMachine {

		@Test
		@DisplayName("ALREADY_REGISTERED 실패는 재시도할 수 없고, 그 외 실패는 재시도 가능")
		void retryRules() {
			file.markFailed(null, RegistrationFailureReason.NETWORK_ERROR);
			file.retryRegistration();
			assertThat(file.getRegisterStatus()).isEqualTo(RegisterStatus.BLOCKCHAIN_PENDING);
			assertThat(file.getDispatchCount()).isEqualTo(2);

			file.markFailed(null, RegistrationFailureReason.ALREADY_REGISTERED);
			assertThatThrownBy(file::retryRegistration).isInstanceOf(RuntimeException.class);
		}

		@Test
		@DisplayName("파기하면 commit 재계산·사용자 연결에 필요한 값이 모두 지워진다")
		void purge() {
			file.purge();
			assertThat(file.getFileHash()).isNull();
			assertThat(file.getSalt()).isNull();
			assertThat(file.getUserSignature()).isNull();
			assertThat(file.getSignerPublicKey()).isNull();
			assertThat(file.getDeletedAt()).isNotNull();
			assertThat(file.getCommit()).isEqualTo(COMMIT); // 체인에 이미 공개된 값
		}
	}
}
