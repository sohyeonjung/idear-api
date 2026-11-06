package com.idear.backend.blockchain.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.blockchain.infrastructure.OnChainCommit;
import com.idear.backend.idea.domain.IdeaFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * "체인에서 직접 읽은 상태"를 기준으로 IdeaFile 상태를 맞추는 단일 진입점.
 * 웹훅 처리와 대사 배치가 같은 규칙을 쓰도록 이 클래스로 모았다.
 *
 * <p>규칙
 * <ol>
 *   <li>체인에 commit 이 있으면 → REGISTERED (웹훅이 failure 라고 해도). 값은 체인에서 읽은 것을 쓴다.</li>
 *   <li>체인에 없고 실패 보고가 있으면 → FAILED (BLOCKCHAIN_PENDING 일 때만)</li>
 *   <li>체인에 없는데 성공 보고만 있으면 → 아무것도 바꾸지 않음 (위조 웹훅 또는 아직 조회 불가)</li>
 *   <li>이미 같은 최종 상태면 → 아무것도 하지 않음 (멱등)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationStateService {

	public enum Outcome {
		CONFIRMED,            // 이번 호출로 REGISTERED 전환
		ALREADY_CONFIRMED,    // 이미 REGISTERED (중복 웹훅 등)
		FAILED,               // 이번 호출로 FAILED 전환
		UNCHANGED,            // 바꿀 근거 없음 (위조 success, 중복 failure 등)
		ONCHAIN_MISMATCH      // 체인 레코드와 DB 값 불일치 → 수동 확인 필요
	}

	private final ApplicationEventPublisher eventPublisher;

	@Transactional(propagation = Propagation.MANDATORY)
	public Outcome apply(IdeaFile ideaFile, Optional<OnChainCommit> onChain,
			RegistrationFailureReason reportedFailure, String reportedTxHash) {

		if (onChain.isPresent()) {
			OnChainCommit record = onChain.get();
			if (!matches(ideaFile, record)) {
				log.error("[RECONCILE] On-chain record mismatch: ideaFileId={}, commit={}, dbSig={}, chainSig={}",
						ideaFile.getIdeaFileId(), ideaFile.getCommit(), ideaFile.getOnChainUserSignature(), record.userSignature());
				return Outcome.ONCHAIN_MISMATCH;
			}
			boolean changed = ideaFile.confirmOnChain(record.txHash(), record.blockNumber(), record.registeredAtMillis());
			if (!changed) {
				return Outcome.ALREADY_CONFIRMED;
			}
			eventPublisher.publishEvent(RegistrationResultEvent.success(ideaFile.getIdeaFileId()));
			return Outcome.CONFIRMED;
		}

		if (reportedFailure == null) {
			return Outcome.UNCHANGED;
		}
		if (reportedFailure == RegistrationFailureReason.ALREADY_REGISTERED) {
			// 게이트웨이는 "이미 있다"고 했는데 체인 조회 결과는 없다 → 모순. 확정하지 않고 대사 배치에 맡긴다.
			log.warn("[RECONCILE] ALREADY_REGISTERED reported but commit not found on chain: {}", ideaFile.getCommit());
			return Outcome.UNCHANGED;
		}
		if (!ideaFile.markFailed(reportedTxHash, reportedFailure)) {
			return Outcome.UNCHANGED;
		}
		eventPublisher.publishEvent(RegistrationResultEvent.failure(ideaFile.getIdeaFileId(), reportedFailure));
		return Outcome.FAILED;
	}

	/** v2 파일은 체인에 기록된 사용자 서명 다이제스트까지 일치해야 같은 등록으로 인정한다. */
	private boolean matches(IdeaFile ideaFile, OnChainCommit record) {
		if (record.commit() != null && !record.commit().equalsIgnoreCase(ideaFile.getCommit())) {
			return false;
		}
		String expected = ideaFile.getOnChainUserSignature();
		if (expected == null || record.userSignature() == null) {
			return true;
		}
		return Objects.equals(expected.toLowerCase(), record.userSignature().toLowerCase());
	}
}
