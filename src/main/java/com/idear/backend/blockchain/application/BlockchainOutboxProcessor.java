package com.idear.backend.blockchain.application;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.blockchain.domain.BlockchainOutbox;
import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.blockchain.infrastructure.BlockchainGatewayClient;
import com.idear.backend.blockchain.infrastructure.BlockchainOutboxRepository;
import com.idear.backend.blockchain.infrastructure.GatewayUnavailableException;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 한 건을 게이트웨이로 전송한다. 행 단위 트랜잭션.
 *
 * <p>같은 commit 을 여러 번 보내도 안전한 이유: 게이트웨이가 등록 전 체인을 조회하고,
 * 컨트랙트가 commit 유니크(require !exists)를 강제한다. 즉 "최소 한 번 전송 + 최종 멱등 보장" 구조다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockchainOutboxProcessor {

	private final BlockchainOutboxRepository outboxRepository;
	private final IdeaFileRepository ideaFileRepository;
	private final BlockchainGatewayClient gatewayClient;
	private final ApplicationEventPublisher eventPublisher;

	@Value("${blockchain.outbox.max-attempts:5}")
	private int maxAttempts = 5;

	@Value("${blockchain.outbox.base-backoff-ms:2000}")
	private long baseBackoffMs = 2000;

	@Value("${blockchain.outbox.max-backoff-ms:300000}")
	private long maxBackoffMs = 300000;

	public enum Result { SENT, RETRY_SCHEDULED, DEAD, SKIPPED }

	@Transactional
	public Result dispatch(Long outboxId) {
		Optional<BlockchainOutbox> locked = outboxRepository.findByIdForUpdate(outboxId);
		if (locked.isEmpty() || locked.get().getStatus() != BlockchainOutbox.Status.PENDING) {
			return Result.SKIPPED; // 다른 인스턴스가 처리 중이거나 이미 처리됨
		}
		BlockchainOutbox outbox = locked.get();

		Optional<IdeaFile> fileOpt = ideaFileRepository.findById(outbox.getIdeaFileId());
		if (fileOpt.isEmpty() || fileOpt.get().getRegisterStatus() != IdeaFile.RegisterStatus.BLOCKCHAIN_PENDING) {
			outbox.markDead("idea file deleted or not pending");
			return Result.SKIPPED;
		}
		IdeaFile file = fileOpt.get();

		try {
			gatewayClient.requestRegistration(
					file.getCommit(),
					file.getRequestedTimestamp(),
					onChainUserSignatureOf(file),
					file.getServerSignature());
			outbox.markSent();
			log.info("[OUTBOX] sent commit={} (attempt {})", outbox.getCommit(), outbox.getAttemptCount());
			return Result.SENT;
		} catch (GatewayUnavailableException e) {
			boolean dead = outbox.markFailedAttempt(e.getMessage(),
					Duration.ofMillis(baseBackoffMs), Duration.ofMillis(maxBackoffMs), maxAttempts);
			if (dead) {
				log.error("[OUTBOX] dead letter commit={} after {} attempts: {}",
						outbox.getCommit(), outbox.getAttemptCount(), e.getMessage());
				if (file.markFailed(null, RegistrationFailureReason.GATEWAY_UNREACHABLE)) {
					eventPublisher.publishEvent(
							RegistrationResultEvent.failure(file.getIdeaFileId(), RegistrationFailureReason.GATEWAY_UNREACHABLE));
				}
				return Result.DEAD;
			}
			log.warn("[OUTBOX] send failed commit={} attempt={} next={}",
					outbox.getCommit(), outbox.getAttemptCount(), outbox.getNextAttemptAt());
			return Result.RETRY_SCHEDULED;
		}
	}

	/** v2 부터는 서명 원문 대신 다이제스트를 체인에 기록한다. 레거시 건은 원래 방식 유지. */
	static String onChainUserSignatureOf(IdeaFile file) {
		return file.getOnChainUserSignature() != null ? file.getOnChainUserSignature() : file.getUserSignature();
	}
}
