package kr.co.ktscholarship.backend.auth.dto;

public record LoginResponse(String accessToken, String role, String name) {
}
