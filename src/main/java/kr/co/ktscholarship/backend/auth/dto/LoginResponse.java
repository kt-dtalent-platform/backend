package kr.co.ktscholarship.backend.auth.dto;

// passwordChangeRequired가 true면 프론트는 비밀번호 변경 화면으로 보낸다
// accessToken이 만료되면 refreshToken으로 /v1/auth/refresh를 호출해 새 쌍을 받는다
public record LoginResponse(
        String accessToken, String refreshToken, String role, String name, boolean passwordChangeRequired
) {
    // 로그에 토큰이 찍히지 않게 한다
    @Override
    public String toString() {
        return "LoginResponse[role=" + role + ", name=" + name + "]";
    }
}
