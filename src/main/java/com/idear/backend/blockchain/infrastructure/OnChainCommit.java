package com.idear.backend.blockchain.infrastructure;

/**
 * 체인에서 직접 읽은 commit 레코드 (FileProof.getCommit 결과 + 이벤트 로그의 txHash).
 * 웹훅 본문이 아니라 이 값을 기준으로 상태를 확정한다.
 */
public record OnChainCommit(
		String commit,
		String txHash,
		Integer blockNumber,
		Long registeredAtSeconds,
		Long timestamp,
		String userSignature,
		String serverSignature
) {
	public Long registeredAtMillis() {
		return registeredAtSeconds == null ? null : registeredAtSeconds * 1000;
	}
}
