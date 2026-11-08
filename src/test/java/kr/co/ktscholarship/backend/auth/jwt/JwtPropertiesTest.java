package kr.co.ktscholarship.backend.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 만료_시간이_양수면_통과한다() {
        assertThat(validator.validate(properties(Duration.ofMinutes(30)))).isEmpty();
    }

    @Test
    void 만료_시간이_0이면_실패한다() {
        assertThat(validator.validate(properties(Duration.ZERO))).hasSize(1);
    }

    @Test
    void 만료_시간이_음수면_실패한다() {
        assertThat(validator.validate(properties(Duration.ofMinutes(-30)))).hasSize(1);
    }

    private JwtProperties properties(Duration expiration) {
        return new JwtProperties("secret", expiration, "kt-scholarship-backend");
    }
}
