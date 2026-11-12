package kr.co.ktscholarship.backend.auth.dto;

// passwordChangeRequired가 true면 프론트는 비밀번호 변경 화면으로 보낸다
public record LoginResponse(String accessToken, String role, String name, boolean passwordChangeRequired) {
}
