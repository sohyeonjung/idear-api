package com.idear.backend.blockchain.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.idear.backend.global.exception.CustomException;
import com.idear.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RegistrationFailureReason {
    ALREADY_REGISTERED("already_registered"),
    SUBMISSION_FAILED("submission_failed"),
    NETWORK_ERROR("network_error"),
    RPC_RATE_LIMIT("rpc_rate_limit"),
    // API 서버 대사 배치가 판단한 실패: 여러 번 재전송했지만 체인에서 끝내 확인되지 않음
    RECONCILIATION_TIMEOUT("reconciliation_timeout"),
    // 게이트웨이 전송 자체가 최대 재시도 후에도 실패
    GATEWAY_UNREACHABLE("gateway_unreachable");

    @JsonValue
    private final String reason;

    @JsonCreator
    public static RegistrationFailureReason fromString(String value) {
        for (RegistrationFailureReason type : RegistrationFailureReason.values()) {
            if (type.reason.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw CustomException.of(ErrorCode.REGISTRATION_FAILURE_REASON_NOT_VALID);
    }
}
