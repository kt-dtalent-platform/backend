package kr.co.ktscholarship.backend.auth.jwt;

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
}
