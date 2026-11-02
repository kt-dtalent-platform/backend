package kr.co.ktscholarship.backend.common.response;

public record ApiError(String code, String message, Object details) {
}
