package kr.co.ktscholarship.backend.cohort;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class CohortRepositoryTest {

    @Autowired
    private CohortRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("INSERT INTO programs (id, name) VALUES (101, '프로그램 A'), (102, '프로그램 B')")
                .update();
        jdbc.sql("""
                INSERT INTO cohorts (id, program_id, name, started_on, ended_on) VALUES
                    (105, 101, '5기', '2025-03-01', '2026-02-28'),
                    (106, 101, '6기', NULL, NULL),
                    (107, 102, '다른 프로그램 기수', '2026-03-01', NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, name, role) VALUES
                    (201, 'scholar1@example.test', 'test-only', '테스트1', 'SCHOLAR'),
                    (202, 'scholar2@example.test', 'test-only', '테스트2', 'SCHOLAR'),
                    (203, 'scholar3@example.test', 'test-only', '테스트3', 'SCHOLAR')
                """).update();
        jdbc.sql("""
                INSERT INTO scholar_profiles (user_id, cohort_id, school_name) VALUES
                    (201, 105, '테스트 학교'),
                    (202, 105, '테스트 학교'),
                    (203, 107, '테스트 학교')
                """).update();
    }

    @Test
    void returnsProgramCohortsWithScholarCountsAndNullableDates() {
        assertThat(repository.findByProgramId(101)).containsExactlyInAnyOrder(
                new CohortItem(105, "5기", LocalDate.of(2025, 3, 1), LocalDate.of(2026, 2, 28), 2),
                new CohortItem(106, "6기", null, null, 0)
        );
        assertThat(repository.findByProgramId(102)).containsExactly(
                new CohortItem(107, "다른 프로그램 기수", LocalDate.of(2026, 3, 1), null, 1)
        );
    }

    @Test
    void checksProgramExistenceIndependentlyOfCohorts() {
        jdbc.sql("INSERT INTO programs (id, name) VALUES (103, '기수 없는 프로그램')").update();

        assertThat(repository.existsProgram(101)).isTrue();
        assertThat(repository.existsProgram(103)).isTrue();
        assertThat(repository.existsProgram(999)).isFalse();
    }
}
