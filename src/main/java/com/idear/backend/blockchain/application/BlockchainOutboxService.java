package com.idear.backend.blockchain.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.blockchain.domain.BlockchainOutbox;
import com.idear.backend.blockchain.infrastructure.BlockchainOutboxRepository;
import com.idear.backend.idea.domain.IdeaFile;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BlockchainOutboxService {

	private final BlockchainOutboxRepository outboxRepository;

	/**
	 * 호출한 쪽(서명 제출, 재시도, 대사 배치)의 트랜잭션에 참여해야 의미가 있다.
	 * 상태 변경과 "보낼 일" 기록이 함께 커밋되거나 함께 롤백된다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(IdeaFile ideaFile) {
		outboxRepository.findByCommit(ideaFile.getCommit())
				.ifPresentOrElse(
						BlockchainOutbox::requeue,
						() -> outboxRepository.save(BlockchainOutbox.pending(ideaFile.getIdeaFileId(), ideaFile.getCommit()))
				);
	}
}
