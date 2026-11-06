package com.idear.backend.blockchain.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.idear.backend.blockchain.domain.BlockchainOutbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BlockchainOutboxRepository extends JpaRepository<BlockchainOutbox, Long> {

	Optional<BlockchainOutbox> findByCommit(String commit);

	@Query("select o.outboxId from BlockchainOutbox o "
			+ "where o.status = com.idear.backend.blockchain.domain.BlockchainOutbox.Status.PENDING "
			+ "and o.nextAttemptAt <= :now order by o.outboxId")
	List<Long> findDispatchableIds(@Param("now") LocalDateTime now, Pageable pageable);

	/**
	 * 서버가 여러 대여도 같은 행을 동시에 보내지 않도록 행 잠금.
	 * lock.timeout = -2 는 Hibernate 에서 SKIP LOCKED (다른 인스턴스가 잡은 행은 건너뜀).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("select o from BlockchainOutbox o where o.outboxId = :id")
	Optional<BlockchainOutbox> findByIdForUpdate(@Param("id") Long id);

	long countByStatus(BlockchainOutbox.Status status);
}
