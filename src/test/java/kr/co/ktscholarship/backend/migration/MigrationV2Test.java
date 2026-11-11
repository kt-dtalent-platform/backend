package kr.co.ktscholarship.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

// V2 migration: id 자동 증가, 업무 중복 방지, 비밀번호 변경 필요 여부
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MigrationV2Test {

    @Autowired
    private JdbcTemplate jdbc;

    private long scholarId;
    private long requestId;

    @BeforeEach
    void setUp() {
        long programId = insertAndGetId("INSERT INTO programs (name) VALUES ('프로그램')");
        long cohortId = insertAndGetId("INSERT INTO cohorts (program_id, name) VALUES (" + programId + ", '6기')");
        long managerId = insertUser("v2-manager@example.test", "MANAGER", null);
        scholarId = insertUser("v2-scholar@example.test", "SCHOLAR", null);
        jdbc.update("INSERT INTO scholar_profiles (user_id, cohort_id, school_name) VALUES (?, ?, '테스트대학교')", scholarId, cohortId);
        long templateId = insertAndGetId(
                "INSERT INTO request_templates (code, name, default_fields) VALUES ('V2_TEST', '템플릿', '[]')");
        jdbc.update("""
                INSERT INTO requests (program_id, cohort_id, template_id, title, academic_year, semester, closes_at, created_by)
                VALUES (?, ?, ?, '성적 제출', 2026, '2', '2026-12-31 14:59:59', ?)
                """, programId, cohortId, templateId, managerId);
        requestId = lastId();
    }

    @Test
    void id를_넣지_않으면_자동으로_생성된다() {
        long first = insertUser("v2-auto1@example.test", "SCHOLAR", null);
        long second = insertUser("v2-auto2@example.test", "SCHOLAR", null);

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void 같은_요청에_같은_장학생의_제출은_하나만_만들_수_있다() {
        jdbc.update("INSERT INTO submissions (request_id, scholar_id, answers) VALUES (?, ?, '{}')", requestId, scholarId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO submissions (request_id, scholar_id, answers) VALUES (?, ?, '{}')", requestId, scholarId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_장학생을_같은_요청_대상에_두_번_넣을_수_없다() {
        jdbc.update("INSERT INTO request_targets (request_id, scholar_id) VALUES (?, ?)", requestId, scholarId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO request_targets (request_id, scholar_id) VALUES (?, ?)", requestId, scholarId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 템플릿_코드는_중복될_수_없다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO request_templates (code, name, default_fields) VALUES ('V2_TEST', '다른 이름', '[]')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 삭제되지_않은_계정끼리는_이메일이_중복될_수_없다() {
        insertUser("v2-dup@example.test", "SCHOLAR", null);

        assertThatThrownBy(() -> insertUser("v2-dup@example.test", "SCHOLAR", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 삭제된_계정의_이메일로는_새_계정을_만들_수_있다() {
        insertUser("v2-reuse@example.test", "SCHOLAR", "2026-11-01 00:00:00");
        insertUser("v2-reuse@example.test", "SCHOLAR", "2026-11-02 00:00:00");

        long active = insertUser("v2-reuse@example.test", "SCHOLAR", null);

        assertThat(active).isPositive();
    }

    @Test
    void 비밀번호_변경_필요_여부의_기본값은_false() {
        long userId = insertUser("v2-password@example.test", "SCHOLAR", null);

        Boolean required = jdbc.queryForObject(
                "SELECT password_change_required FROM users WHERE id = ?", Boolean.class, userId);

        assertThat(required).isFalse();
    }

    private long insertUser(String email, String role, String deletedAt) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, name, role, deleted_at)
                VALUES (?, 'test-only', '테스트', ?, ?)
                """, email, role, deletedAt);
        return lastId();
    }

    private long insertAndGetId(String sql) {
        jdbc.update(sql);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
