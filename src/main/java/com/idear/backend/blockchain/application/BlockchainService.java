package com.idear.backend.blockchain.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.blockchain.domain.RegistrationStatus;
import com.idear.backend.blockchain.dto.request.RegistrationResultRequest;
import com.idear.backend.blockchain.infrastructure.BlockchainGatewayClient;
import com.idear.backend.blockchain.infrastructure.GatewayUnavailableException;
import com.idear.backend.blockchain.infrastructure.OnChainCommit;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 블록체인 게이트웨이 웹훅 처리.
 *
 * <p>웹훅 엔드포인트는 게이트웨이가 호출해야 하므로 공개(permitAll)되어 있고, 게이트웨이(별도 저장소)는
 * 서명 헤더를 보내지 않는다. 그래서 <b>웹훅 본문을 믿지 않는다</b>. 웹훅은 "이 commit 을 확인해 달라"는
 * 신호로만 쓰고, 상태는 체인에서 직접 조회한 값으로 확정한다.
 * <ul>
 *   <li>위조 success → 체인에 없으므로 무시</li>
 *   <li>위조/오판 failure → 체인에 있으면 성공으로 확정, 없으면 실패 기록 후 대사 배치가 재확인</li>
 *   <li>중복 웹훅 → 이미 같은 상태면 아무것도 하지 않음 (멱등)</li>
 * </ul>
 * 게이트웨이 조회가 실패하면 상태를 바꾸지 않는다. 대사 배치가 나중에 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockchainService {

	private final IdeaFileRepository ideaFileRepository;
	private final BlockchainGatewayClient gatewayClient;
	private final RegistrationStateService registrationStateService;

	@Transactional
	public RegistrationStateService.Outcome handleRegistrationResult(RegistrationResultRequest request) {
		log.info("Received webhook: status={}, commit={}, txHash={}",
				request.getStatus(), request.getCommit(), request.getTxHash());

		if (request.getCommit() == null || request.getStatus() == null) {
			return RegistrationStateService.Outcome.UNCHANGED;
		}

		Optional<IdeaFile> found = ideaFileRepository.findIdeaFileByCommit(request.getCommit());
		if (found.isEmpty()) {
			// 존재 여부를 응답으로 노출하지 않는다(commit 존재 확인 오라클 방지). 파기된 파일도 여기로 온다.
			log.warn("Webhook for unknown commit ignored: {}", request.getCommit());
			return RegistrationStateService.Outcome.UNCHANGED;
		}
		IdeaFile ideaFile = found.get();

		Optional<OnChainCommit> onChain;
		try {
			onChain = gatewayClient.findCommit(request.getCommit());
		} catch (GatewayUnavailableException e) {
			log.warn("Cannot verify webhook against chain, deferring to reconciliation: commit={}, error={}",
					request.getCommit(), e.getMessage());
			return RegistrationStateService.Outcome.UNCHANGED;
		}

		RegistrationFailureReason reportedFailure = null;
		if (request.getStatus() == RegistrationStatus.FAILURE) {
			reportedFailure = request.getFailureData() != null && request.getFailureData().getReason() != null
					? request.getFailureData().getReason()
					: RegistrationFailureReason.SUBMISSION_FAILED;
		}

		RegistrationStateService.Outcome outcome =
				registrationStateService.apply(ideaFile, onChain, reportedFailure, request.getTxHash());

		if (request.getStatus() == RegistrationStatus.SUCCESS && onChain.isEmpty()) {
			log.warn("[SECURITY] SUCCESS webhook not backed by chain state (forged or not yet visible): commit={}, txHash={}",
					request.getCommit(), request.getTxHash());
		}
		log.info("Webhook processed: commit={}, outcome={}", request.getCommit(), outcome);
		return outcome;
	}
}
