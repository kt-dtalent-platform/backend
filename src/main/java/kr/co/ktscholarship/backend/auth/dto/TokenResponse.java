package kr.co.ktscholarship.backend.auth.dto;

public record TokenResponse(String accessToken, String refreshToken) {
    @Override
    public String toString() {
        return "TokenResponse[****]";
    }
}
