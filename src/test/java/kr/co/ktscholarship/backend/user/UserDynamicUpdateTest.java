package kr.co.ktscholarship.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 변경은 사용자 행을 잠그지 않고 읽는다. 읽은 뒤 커밋 전에 다른 요청(로그인 실패)이 실패 횟수·잠금을 바꾸면,
 * 모든 칸을 다시 쓰는 UPDATE는 그 값을 옛 값으로 되돌린다. 바뀐 칸만 쓰는지 확인한다
 */
@SpringBootTest
@ActiveProfiles("test")
class UserDynamicUpdateTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role, status, failed_login_count)
            VALUES (9601, '동시 변경', 'dynamic-update@example.test', 'old', 'SCHOLAR', 'ACTIVE', 0)
            """).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM users WHERE id = 9601").update();
    }

    @Test
    void passwordChangeKeepsFailuresAndLockWrittenAfterItWasRead() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Instant lockedUntil = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        outer.executeWithoutResult(status -> {
            User user = users.findByIdAndDeletedAtIsNull(9601L).orElseThrow();
            // 비밀번호 변경이 사용자를 읽은 뒤, 다른 요청이 5번째 실패로 계정을 잠그고 먼저 커밋한다
            inner.executeWithoutResult(other -> jdbc.sql("""
                UPDATE users SET failed_login_count = 5, status = 'LOCKED', locked_until = :until WHERE id = 9601
                """).param("until", kr.co.ktscholarship.backend.common.util.UtcDateTimes.toDb(lockedUntil)).update());
            user.changePassword("new-hash", Instant.now().truncatedTo(ChronoUnit.SECONDS));
        });

        var row = jdbc.sql("SELECT password_hash, failed_login_count, status FROM users WHERE id = 9601")
            .query().singleRow();
        assertThat(row.get("password_hash")).isEqualTo("new-hash");
        assertThat(((Number) row.get("failed_login_count")).intValue()).isEqualTo(5);
        assertThat(row.get("status")).isEqualTo("LOCKED");
    }
}
