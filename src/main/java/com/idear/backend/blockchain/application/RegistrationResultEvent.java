package com.idear.backend.blockchain.application;

import com.idear.backend.blockchain.domain.RegistrationFailureReason;

/**
 * 등록 상태가 최종(REGISTERED/FAILED)으로 바뀌었다는 도메인 이벤트.
 * 증명서 생성·알림·이메일 같은 부가 작업은 상태 변경 트랜잭션이 커밋된 뒤에만 실행한다.
 */
public record RegistrationResultEvent(Long ideaFileId, boolean success, RegistrationFailureReason failureReason) {

	public static RegistrationResultEvent success(Long ideaFileId) {
		return new RegistrationResultEvent(ideaFileId, true, null);
	}

	public static RegistrationResultEvent failure(Long ideaFileId, RegistrationFailureReason reason) {
		return new RegistrationResultEvent(ideaFileId, false, reason);
	}
}
