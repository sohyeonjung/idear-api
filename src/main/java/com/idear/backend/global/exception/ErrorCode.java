package com.idear.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Global
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "G001", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "G002", "유효하지 않은 입력입니다."),

    // Auth
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A002", "접근 권한이 없습니다."),
    EXPIRED_TOKEN(HttpStatus.BAD_REQUEST, "A003", "이미 만료된 토큰입니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "A004", "유효하지 않은 엑세스 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "A005", "유효하지 않은 리프레시 토큰입니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U001", "존재하지 않는 유저입니다."),
    USER_DELETED(HttpStatus.NOT_FOUND, "U002", "탈퇴 처리된 유저입니다."),
    USER_NOT_OWNER(HttpStatus.NOT_FOUND, "U003", "요청 대상 자원의 소유자가 아닙니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "U004", "이미 사용 중인 이메일입니다."),
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "U005", "유효하지 않은 인증 코드입니다."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "U006", "인증 코드가 만료되었습니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "U007", "이메일 인증이 완료되지 않았습니다."),
    INVALID_IMAGE_FILE(HttpStatus.BAD_REQUEST, "U008", "유효하지 않은 이미지 파일입니다."),
    IMAGE_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "U009", "이미지 파일 크기가 허용된 용량을 초과합니다."),

    // Idea
    FILE_UPLOAD_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "F001", "파일 업로드를 실패했습니다."),
    FILE_NOTFOUND_ERROR(HttpStatus.NOT_FOUND, "F002", "존재하지 않는 파일입니다."),
    FILE_DELETE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "F003", "파일 삭제를 실패했습니다."),
    IDEA_NOT_FOUND(HttpStatus.NOT_FOUND, "I001", "존재하지 않는 아이디어 입니다."),
    IDEA_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "I002", "존재하지 않는 아이디어 파일입니다."),
    IDEA_FILE_STATUS_MISMATCH(HttpStatus.CONFLICT, "I003", "아이디어 파일의 현재 상태가 요청과 일치하지 않습니다."),
    IDEA_FILE_IDEA_MISMATCH(HttpStatus.NOT_FOUND, "I004", "요청 아이디어에 해당하는 아이디어 파일이 아닙니다."),
    IDEA_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "I005", "존재하지 않는 아이디어 버전입니다."),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "I006", "존재하지 않는 태그입니다."),

    // Crawling
    CRAWLING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "크롤링 중 오류가 발생했습니다."),
    PAGE_PARSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "페이지 파싱에 실패했습니다."),
    CONTEST_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "공모전 저장에 실패했습니다."),
    CONTEST_NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "존재하지 않는 공모전입니다."),

    // Alert
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "AL001", "존재하지 않는 알림입니다."),

    // Blockchain
    REGISTRATION_STATUS_NOT_VALID(HttpStatus.BAD_REQUEST, "B001", "유효하지 않은 status 입니다."),
    REGISTRATION_FAILURE_REASON_NOT_VALID(HttpStatus.BAD_REQUEST, "B002", "유효하지 않은 reason 입니다."),
    PUBLIC_KEY_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "B003", "전자서명용 공개키가 등록되지 않았습니다."),
    INVALID_PUBLIC_KEY(HttpStatus.BAD_REQUEST, "B004", "유효하지 않은 공개키입니다. (ECDSA P-256 SPKI Base64 필요)"),
    INVALID_USER_SIGNATURE(HttpStatus.BAD_REQUEST, "B005", "사용자 전자서명 검증에 실패했습니다."),
    REGISTRATION_NOT_RETRYABLE(HttpStatus.CONFLICT, "B006", "재시도할 수 없는 등록 상태입니다."),
    PROOF_NOT_AVAILABLE(HttpStatus.CONFLICT, "B007", "블록체인 등록이 완료된 파일만 증명 자료를 발급할 수 있습니다."),

    // Certificate
    CERTIFICATE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CT001", "증명서 생성에 실패했습니다."),
    CERTIFICATE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CT002", "증명서 업로드에 실패했습니다."),
    FILE_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "CT003", "블록체인 등록이 완료되지 않은 파일입니다."),
    LATEST_VERSION_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "CT004", "최신 버전의 파일에 대해 블록체인 등록이 완료되지 않았습니다."),

    // Inquiry
    NOT_FOUND_INQUIRY(HttpStatus.NOT_FOUND, "Q001", "존재하지 않는 문의입니다."),
    ALREADY_ANSWERED(HttpStatus.BAD_REQUEST, "Q002", "이미 답변된 문의입니다."),
    TOO_MANY_INQUIRY_IMAGES(HttpStatus.BAD_REQUEST, "Q003", "문의 이미지는 최대 4장까지 첨부 가능합니다."),
    INVALID_INQUIRY_IMAGE_FILE(HttpStatus.BAD_REQUEST, "Q004", "유효하지 않은 이미지 파일입니다."),
    CANNOT_UPDATE_INQUIRY(HttpStatus.BAD_REQUEST, "Q005", "접수 상태의 문의만 수정할 수 있습니다."),
    CANNOT_DELETE_INQUIRY(HttpStatus.BAD_REQUEST, "Q006", "접수 상태의 문의만 삭제할 수 있습니다."),

    // Email
    EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "E001", "이메일 전송에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
