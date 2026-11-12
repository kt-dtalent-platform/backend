package kr.co.ktscholarship.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다."),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "형식이 올바르지 않은 항목이 있습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "VERSION_CONFLICT", "다른 화면에서 변경되었습니다. 새로고침 후 다시 시도하세요."),
    SUBMISSION_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_LOCKED", "수정할 수 없는 제출입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    SCHOLAR_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHOLAR_NOT_FOUND", "장학생을 찾을 수 없습니다."),
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "요청을 찾을 수 없거나 아직 공개되지 않았습니다."),
    NOT_A_TARGET(HttpStatus.FORBIDDEN, "NOT_A_TARGET", "본인이 대상자가 아닌 요청입니다."),
    PROGRAM_NOT_FOUND(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND", "프로그램을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "허용되지 않은 HTTP 메서드입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 없거나 만료되었습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "사용할 수 없는 계정입니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "잠긴 계정입니다. 담당자에게 문의하세요."),
    // 401은 "로그인 안 됨"이라 프론트가 로그아웃시키므로, 입력 실수는 400으로 응답한다
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다."),
    SAME_AS_CURRENT(HttpStatus.BAD_REQUEST, "SAME_AS_CURRENT", "기존 비밀번호와 동일합니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
