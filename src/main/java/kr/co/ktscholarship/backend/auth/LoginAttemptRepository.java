package kr.co.ktscholarship.backend.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.user.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 로그인 실패 횟수와 잠금. 비밀번호를 5번 연속 틀리면 30분 동안 잠그고, 시간이 지나면 저절로 풀린다(담당자 해제 기능 없음).
 *
 * <p>같은 계정으로 동시에 로그인해도 실패 횟수가 빠지지 않게 사용자 행을 잠근 뒤 읽고 고친다.
 * locked_until이 비어 있는 LOCKED(임시 비밀번호 재발급이 잠금 시각을 지운 경우)는 잠금이 끝난 것으로 본다
 */
@Repository
class LoginAttemptRepository {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final JdbcClient jdbc;

    LoginAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    State lock(long userId) {
        // 비밀번호 해시도 잠근 뒤에 읽는다. 잠금을 기다리는 동안 비밀번호가 바뀌었으면 새 해시로 확인해야 한다
        return jdbc.sql("SELECT status, failed_login_count, locked_until, password_hash FROM users WHERE id = :id FOR UPDATE")
            .param("id", userId)
            .query((rs, n) -> new State(
                UserStatus.valueOf(rs.getString("status")),
                rs.getInt("failed_login_count"),
                UtcDateTimes.fromDb(rs.getObject("locked_until", LocalDateTime.class)),
                rs.getString("password_hash")))
            .single();
    }

    /** 실패를 하나 더 센다. 이번 실패로 잠겼으면 잠금이 풀리는 시각, 아니면 null */
    Instant recordFailure(long userId, State state, Instant now) {
        // 잠금이 끝난 계정은 0부터 다시 센다
        int failures = (state.status() == UserStatus.LOCKED ? 0 : state.failures()) + 1;
        Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
        jdbc.sql("""
            UPDATE users SET status = :status, failed_login_count = :failures, locked_until = :until,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """)
            .param("status", lockedUntil != null ? UserStatus.LOCKED.name() : UserStatus.ACTIVE.name())
            .param("failures", failures)
            .param("until", lockedUntil == null ? null : UtcDateTimes.toDb(lockedUntil))
            .param("id", userId)
            .update();
        return lockedUntil;
    }

    /** 로그인 성공: 실패 횟수를 0으로, 끝난 잠금은 ACTIVE로 */
    void succeed(long userId, State state) {
        if (state.failures() == 0 && state.status() != UserStatus.LOCKED && state.lockedUntil() == null) {
            return;
        }
        jdbc.sql("""
            UPDATE users SET status = 'ACTIVE', failed_login_count = 0, locked_until = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """)
            .param("id", userId)
            .update();
    }

    record State(UserStatus status, int failures, Instant lockedUntil, String passwordHash) {

        @Override
        public String toString() {
            return "State[status=" + status + ", failures=" + failures + ", lockedUntil=" + lockedUntil + "]";
        }

        boolean lockedAt(Instant now) {
            return status == UserStatus.LOCKED && lockedUntil != null && lockedUntil.isAfter(now);
        }
    }
}
