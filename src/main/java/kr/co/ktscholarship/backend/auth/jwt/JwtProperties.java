package kr.co.ktscholarship.backend.auth.jwt;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// application.properties의 app.jwt.* 값을 묶어 받는다. 값이 비어 있으면 서버 시작 시 실패한다.
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenExpiration,
        @NotBlank String issuer
) {

    // 0 이하이면 발급 즉시 만료된 토큰이 나가므로 서버 시작 단계에서 막는다
    @AssertTrue(message = "app.jwt.access-token-expiration은 0보다 커야 합니다")
    public boolean isAccessTokenExpirationPositive() {
        return accessTokenExpiration == null || accessTokenExpiration.isPositive();
    }
}
