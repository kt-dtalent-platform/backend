package kr.co.ktscholarship.backend.auth.token;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 서명·만료가 맞아도 지금 계정 상태로 다시 확인한다.
 * 삭제·사용 중지된 계정, 그리고 비밀번호를 바꾸기 전(tokens_valid_after 전)에 발급된 토큰은 거절한다.
 * 잠김(LOCKED)은 새 로그인만 막고 이미 로그인한 세션은 끊지 않는다 (잠금은 비밀번호 추측 방지용)
 */
public class AccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "계정 상태로 쓸 수 없는 토큰", null);

    private final JdbcClient jdbc;

    public AccountTokenValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Optional<Account> account = find(token.getSubject());
        if (account.isEmpty() || account.get().status().equals("INACTIVE")) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        Instant validAfter = account.get().tokensValidAfter();
        Instant issuedAt = token.getIssuedAt();
        if (validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter))) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private Optional<Account> find(String subject) {
        long id;
        try {
            id = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT status, tokens_valid_after FROM users WHERE id = :id AND deleted_at IS NULL")
            .param("id", id)
            .query((row, index) -> new Account(row.getString("status"),
                UtcDateTimes.fromDb(row.getObject("tokens_valid_after", LocalDateTime.class))))
            .optional();
    }

    private record Account(String status, Instant tokensValidAfter) {
    }
}
