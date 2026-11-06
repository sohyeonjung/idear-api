package com.idear.backend.blockchain.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.blockchain.infrastructure.BlockchainGatewayClient;
import com.idear.backend.blockchain.infrastructure.GatewayUnavailableException;
import com.idear.backend.blockchain.infrastructure.OnChainCommit;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 온체인·DB 대사(reconciliation). 파일 한 건 단위 트랜잭션.
 *
 * <p>웹훅은 유실될 수 있다(게이트웨이 WebSocket 끊김, 웹훅 전송 실패, API 재배포 중 수신 실패,
 * 게이트웨이 최종 실패 핸들러 미동작). 그래서 결과를 "기다리지" 않고 주기적으로 체인과 직접 비교한다.
 * <ul>
 *   <li>BLOCKCHAIN_PENDING + 체인에 있음 → REGISTERED 복구</li>
 *   <li>BLOCKCHAIN_PENDING + 체인에 없음 + 오래됨 → 재전송 (최대 횟수 초과 시 FAILED)</li>
 *   <li>FAILED + 체인에 있음 → REGISTERED 복구 (타임아웃을 실패로 오판한 경우)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockchainReconciler {

	public enum Result { RECOVERED, REDISPATCHED, TIMED_OUT, STILL_PENDING, NO_CHANGE, GATEWAY_DOWN, MISMATCH }

	private final IdeaFileRepository ideaFileRepository;
	private final BlockchainGatewayClient gatewayClient;
	private final RegistrationStateService registrationStateService;
	private final BlockchainOutboxService outboxService;
	private final ApplicationEventPublisher eventPublisher;

	/** 이 시간 동안 체인에서 확인되지 않으면 재전송 */
	@Value("${blockchain.reconciliation.stuck-threshold-seconds:600}")
	private long stuckThresholdSeconds = 600;

	/** 재전송 포함 최대 전송 횟수 */
	@Value("${blockchain.reconciliation.max-dispatch:3}")
	private int maxDispatch = 3;

	@Transactional
	public Result reconcile(Long ideaFileId) {
		Optional<IdeaFile> fileOpt = ideaFileRepository.findById(ideaFileId);
		if (fileOpt.isEmpty()) {
			return Result.NO_CHANGE;
		}
		IdeaFile file = fileOpt.get();
		IdeaFile.RegisterStatus status = file.getRegisterStatus();
		if (status != IdeaFile.RegisterStatus.BLOCKCHAIN_PENDING && status != IdeaFile.RegisterStatus.FAILED) {
			return Result.NO_CHANGE;
		}

		Optional<OnChainCommit> onChain;
		try {
			onChain = gatewayClient.findCommit(file.getCommit());
		} catch (GatewayUnavailableException e) {
			return Result.GATEWAY_DOWN;
		}

		if (onChain.isPresent()) {
			RegistrationStateService.Outcome outcome = registrationStateService.apply(file, onChain, null, null);
			if (outcome == RegistrationStateService.Outcome.CONFIRMED) {
				log.warn("[RECONCILE] recovered from {}: ideaFileId={}, commit={}", status, ideaFileId, file.getCommit());
				return Result.RECOVERED;
			}
			return outcome == RegistrationStateService.Outcome.ONCHAIN_MISMATCH ? Result.MISMATCH : Result.NO_CHANGE;
		}

		if (status == IdeaFile.RegisterStatus.FAILED) {
			return Result.NO_CHANGE;
		}

		LocalDateTime changedAt = file.getStatusChangedAt();
		boolean stuck = changedAt == null
				|| changedAt.isBefore(LocalDateTime.now().minus(Duration.ofSeconds(stuckThresholdSeconds)));
		if (!stuck) {
			return Result.STILL_PENDING;
		}

		if (file.getDispatchCount() >= maxDispatch) {
			if (file.markFailed(null, RegistrationFailureReason.RECONCILIATION_TIMEOUT)) {
				eventPublisher.publishEvent(
						RegistrationResultEvent.failure(file.getIdeaFileId(), RegistrationFailureReason.RECONCILIATION_TIMEOUT));
			}
			log.error("[RECONCILE] timed out after {} dispatches: ideaFileId={}", file.getDispatchCount(), ideaFileId);
			return Result.TIMED_OUT;
		}

		file.markRedispatched();
		outboxService.enqueue(file);
		log.warn("[RECONCILE] redispatch #{}: ideaFileId={}, commit={}", file.getDispatchCount(), ideaFileId, file.getCommit());
		return Result.REDISPATCHED;
	}
}
