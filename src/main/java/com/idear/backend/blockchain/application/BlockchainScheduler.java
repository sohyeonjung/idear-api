package com.idear.backend.blockchain.application;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.idear.backend.blockchain.infrastructure.BlockchainOutboxRepository;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 릴레이 + 대사 배치 스케줄러. (blockchain.scheduler.enabled=false 로 끌 수 있음)
 * 각 작업은 한 건씩 별도 트랜잭션으로 처리해 한 건의 실패가 전체를 막지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blockchain.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BlockchainScheduler {

	private final BlockchainOutboxRepository outboxRepository;
	private final BlockchainOutboxProcessor outboxProcessor;
	private final IdeaFileRepository ideaFileRepository;
	private final BlockchainReconciler reconciler;

	@Value("${blockchain.outbox.batch-size:20}")
	private int outboxBatchSize;

	@Value("${blockchain.reconciliation.batch-size:100}")
	private int reconcileBatchSize;

	/** 등록 직후 정상 경로(웹훅)에 기회를 주는 유예 시간 */
	@Value("${blockchain.reconciliation.grace-seconds:120}")
	private long graceSeconds;

	/** FAILED 건을 다시 확인하는 기간 */
	@Value("${blockchain.reconciliation.failed-lookback-hours:24}")
	private long failedLookbackHours;

	@Scheduled(fixedDelayString = "${blockchain.outbox.relay-interval-ms:2000}")
	public void relayOutbox() {
		List<Long> ids = outboxRepository.findDispatchableIds(LocalDateTime.now(), PageRequest.of(0, outboxBatchSize));
		for (Long id : ids) {
			try {
				outboxProcessor.dispatch(id);
			} catch (Exception e) {
				log.error("[OUTBOX] unexpected error outboxId={}", id, e);
			}
		}
	}

	@Scheduled(fixedDelayString = "${blockchain.reconciliation.interval-ms:60000}",
			initialDelayString = "${blockchain.reconciliation.initial-delay-ms:30000}")
	public void reconcile() {
		LocalDateTime now = LocalDateTime.now();
		List<Long> pending = ideaFileRepository.findIdsByStatusChangedBefore(
				IdeaFile.RegisterStatus.BLOCKCHAIN_PENDING, now.minusSeconds(graceSeconds), PageRequest.of(0, reconcileBatchSize));
		List<Long> failed = ideaFileRepository.findIdsByStatusChangedAfter(
				IdeaFile.RegisterStatus.FAILED, now.minusHours(failedLookbackHours), PageRequest.of(0, reconcileBatchSize));

		Map<BlockchainReconciler.Result, Integer> summary = new EnumMap<>(BlockchainReconciler.Result.class);
		for (Long id : concat(pending, failed)) {
			try {
				summary.merge(reconciler.reconcile(id), 1, Integer::sum);
			} catch (Exception e) {
				log.error("[RECONCILE] unexpected error ideaFileId={}", id, e);
			}
		}
		if (!summary.isEmpty()) {
			// 대사 결과는 운영 지표로 쓴다: RECOVERED 가 0 이 아니면 정상 경로(웹훅)에서 유실이 있었다는 뜻
			log.info("[RECONCILE] checked pending={}, failed={}, result={}", pending.size(), failed.size(), summary);
		}
	}

	private static List<Long> concat(List<Long> a, List<Long> b) {
		return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList();
	}
}
