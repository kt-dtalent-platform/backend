package kr.co.ktscholarship.backend.auth.dto;

import java.time.Instant;

// 비밀번호를 바꾸면 이전 토큰은 모두 끊기므로, 이 기기에서 계속 쓸 새 토큰 쌍을 함께 준다
public record PasswordChangeResponse(Instant passwordChangedAt, String accessToken, String refreshToken) {
    @Override
    public String toString() {
        return "PasswordChangeResponse[passwordChangedAt=" + passwordChangedAt + "]";
    }
}
