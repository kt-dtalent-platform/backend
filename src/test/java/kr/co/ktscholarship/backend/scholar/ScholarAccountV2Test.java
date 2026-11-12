package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.dao.DuplicateKeyException;
import kr.co.ktscholarship.backend.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

// V2가 적용되는 전용 MySQL 테스트 DB에서 실행한다.
@EnabledIfEnvironmentVariable(named = "KT13_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
@SpringBootTest(properties = "spring.datasource.url=${KT13_MYSQL_TEST_URL}")
@ActiveProfiles("test")
class ScholarAccountV2Test {
    @Autowired ScholarRegistrationRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;

    @Test
    void accountCreationRequiresAnOuterTransaction() {
        assertThatThrownBy(() -> insert("no-tx@example.test"))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void generatesDistinctIdsAndAllowsReuseOnlyAfterDeletion() {
        transaction.executeWithoutResult(tx -> {
            String email = "kt13-reuse@example.test";
            long id = insert(email);
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM users WHERE id = ? AND role = 'SCHOLAR'
                AND password_change_required = TRUE AND status = 'INACTIVE'
                """, Integer.class, id)).isOne();
            assertThatThrownBy(() -> insert(email))
                .isInstanceOf(DuplicateKeyException.class);
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", id);
            assertThat(insert(email)).isGreaterThan(id);
            tx.setRollbackOnly();
        });
    }

    @Test
    void failedRegistrationRollsBackAccount() {
        String email = "kt13-rollback@example.test";
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            insert(email);
            throw new IllegalStateException("profile failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
            .isZero();
    }

    private long insert(String email) {
        return accounts.insertAccount("테스트", email, "test-hash", UserStatus.INACTIVE);
    }
}
