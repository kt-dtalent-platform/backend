package kr.co.ktscholarship.backend.auth.token;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * refresh token은 원문 대신 해시만 저장한다. DB가 새어도 토큰으로 쓸 수 없게.
 *
 * <p>revoked_at은 "재발급에 쓰여 새 토큰으로 교체됨"(또는 재사용 감지로 일괄 폐기)에만 쓴다.
 * 로그아웃·비밀번호 변경은 행을 지운다. 그래야 폐기된 토큰이 다시 오는 것을 탈취 신호로 정확히 볼 수 있다
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
            INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at)
            VALUES (:user, :hash, :expires, :now)
            """)
            .param("user", userId)
            .param("hash", tokenHash)
            .param("expires", UtcDateTimes.toDb(expiresAt))
            .param("now", UtcDateTimes.toDb(now))
            .update();
    }

    /** 같은 토큰으로 동시에 재발급해도 한 요청만 통과하도록 행을 잠근다 */
    public Optional<Stored> lock(String tokenHash) {
        return jdbc.sql("""
            SELECT id, user_id, expires_at, revoked_at, created_at FROM refresh_tokens
            WHERE token_hash = :hash FOR UPDATE
            """)
            .param("hash", tokenHash)
            .query((row, index) -> new Stored(row.getLong("id"), row.getLong("user_id"),
                UtcDateTimes.fromDb(row.getObject("expires_at", LocalDateTime.class)),
                UtcDateTimes.fromDb(row.getObject("revoked_at", LocalDateTime.class)),
                UtcDateTimes.fromDb(row.getObject("created_at", LocalDateTime.class))))
            .optional();
    }

    public void revoke(long id, Instant now) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
            .param("now", UtcDateTimes.toDb(now))
            .param("id", id)
            .update();
    }

    public void revokeAll(long userId, Instant now) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :user AND revoked_at IS NULL")
            .param("now", UtcDateTimes.toDb(now))
            .param("user", userId)
            .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM refresh_tokens WHERE id = :id").param("id", id).update();
    }

    public void deleteAll(long userId) {
        jdbc.sql("DELETE FROM refresh_tokens WHERE user_id = :user").param("user", userId).update();
    }

    public record Stored(long id, long userId, Instant expiresAt, Instant revokedAt, Instant createdAt) {
    }
}
