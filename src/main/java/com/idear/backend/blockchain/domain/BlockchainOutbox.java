package com.idear.backend.blockchain.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Transactional Outbox.
 *
 * <p>기존: 서명 제출 트랜잭션 안에서 게이트웨이에 HTTP 요청 → 실패해도 예외를 삼킴.
 * 게이트웨이가 죽어 있으면 DB 는 BLOCKCHAIN_PENDING 인데 요청은 사라졌고(유실),
 * HTTP 성공 후 DB 커밋이 실패하면 체인에만 기록이 남았다(역전).
 *
 * <p>개선: 서명 제출 트랜잭션에서는 이 테이블에 "보낼 일"만 기록하고 커밋한다.
 * 별도 릴레이가 커밋된 행만 읽어 전송하므로, DB 상태와 전송 의도가 항상 함께 남는다.
 * commit 에 유니크 제약을 걸어 같은 파일에 대한 전송 작업이 중복 생성되지 않게 한다(멱등키).
 */
@Entity
@Table(name = "blockchain_outbox",
		uniqueConstraints = @UniqueConstraint(name = "uk_blockchain_outbox_commit", columnNames = "commit_value"),
		indexes = @Index(name = "idx_blockchain_outbox_dispatch", columnList = "status, next_attempt_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockchainOutbox {

	public enum Status {
		PENDING,   // 전송 대기
		SENT,      // 게이트웨이가 접수(202)함. 이후 결과는 웹훅/대사 배치가 확인
		DEAD       // 최대 재시도 초과. 운영자 확인 필요(Dead Letter)
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long outboxId;

	@Column(nullable = false)
	private Long ideaFileId;

	@Column(name = "commit_value", length = 66, nullable = false)
	private String commit;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Integer attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	@Column(length = 500)
	private String lastError;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private LocalDateTime sentAt;

	public static BlockchainOutbox pending(Long ideaFileId, String commit) {
		BlockchainOutbox outbox = new BlockchainOutbox();
		outbox.ideaFileId = ideaFileId;
		outbox.commit = commit;
		outbox.status = Status.PENDING;
		outbox.attemptCount = 0;
		outbox.createdAt = LocalDateTime.now();
		outbox.nextAttemptAt = outbox.createdAt;
		return outbox;
	}

	/** 같은 commit 을 다시 보내야 할 때(대사 배치 재전송, 사용자 재시도) 행을 재사용한다. */
	public void requeue() {
		this.status = Status.PENDING;
		this.attemptCount = 0;
		this.lastError = null;
		this.nextAttemptAt = LocalDateTime.now();
	}

	public void markSent() {
		this.status = Status.SENT;
		this.attemptCount = this.attemptCount + 1;
		this.sentAt = LocalDateTime.now();
		this.lastError = null;
	}

	/**
	 * 전송 실패 기록. 지수 백오프로 다음 시도 시각을 정하고, 최대 횟수를 넘으면 DEAD.
	 *
	 * @return DEAD 로 전환됐으면 true
	 */
	public boolean markFailedAttempt(String error, Duration baseBackoff, Duration maxBackoff, int maxAttempts) {
		this.attemptCount = this.attemptCount + 1;
		this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
		if (this.attemptCount >= maxAttempts) {
			this.status = Status.DEAD;
			return true;
		}
		long factor = 1L << Math.min(this.attemptCount - 1, 20);
		Duration backoff = baseBackoff.multipliedBy(factor);
		if (backoff.compareTo(maxBackoff) > 0) {
			backoff = maxBackoff;
		}
		this.nextAttemptAt = LocalDateTime.now().plus(backoff);
		return false;
	}

	public void markDead(String reason) {
		this.status = Status.DEAD;
		this.lastError = reason;
	}
}
