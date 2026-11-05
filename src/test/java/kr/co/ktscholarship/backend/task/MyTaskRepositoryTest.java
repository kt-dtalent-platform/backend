package kr.co.ktscholarship.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import kr.co.ktscholarship.backend.task.MyTaskRepository.Filter;
import kr.co.ktscholarship.backend.task.MyTaskRepository.Sort;
import kr.co.ktscholarship.backend.task.MyTaskRepository.TaskRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MyTaskRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MyTaskRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.sql("INSERT INTO programs (id, name) VALUES (1, '테스트 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (6, 1, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (10, '담당자', 'manager@example.test', 'test-only', 'MANAGER'),
                (101, '장학생', 'scholar@example.test', 'test-only', 'SCHOLAR'),
                (102, '다른 장학생', 'other@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name) VALUES
                (101, 6, '테스트 학교'), (102, 6, '테스트 학교')
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (1, 'TEST_TEMPLATE', '검증용', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests
                (id, program_id, template_id, title, academic_year, semester, closes_at, status, created_by, created_at)
            VALUES
                (31, 1, 1, '요청1', 2026, '2', '2026-11-11', 'IN_PROGRESS', 10, '2026-11-01'),
                (32, 1, 1, '요청2', 2026, '2', '2026-11-12', 'IN_PROGRESS', 10, '2026-11-02'),
                (33, 1, 1, '요청3', 2026, '2', '2026-11-13', 'IN_PROGRESS', 10, '2026-11-03'),
                (34, 1, 1, '요청4', 2026, '2', '2026-11-14', 'IN_PROGRESS', 10, '2026-11-04'),
                (35, 1, 1, '요청5', 2026, '2', '2026-11-15', 'IN_PROGRESS', 10, '2026-11-05'),
                (36, 1, 1, '요청6', 2026, '2', '2026-11-16', 'IN_PROGRESS', 10, '2026-11-06'),
                (37, 1, 1, '다른 대상', 2026, '2', '2026-11-17', 'IN_PROGRESS', 10, '2026-11-07'),
                (38, 1, 1, '마감 요청', 2026, '2', '2026-11-18', 'CLOSED', 10, '2026-11-08'),
                (39, 1, 1, '공개 전', 2026, '2', '2026-11-19', 'DRAFT', 10, '2026-11-09')
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (id, request_id, scholar_id) VALUES
                (1, 31, 101), (2, 31, 101), (3, 32, 101), (4, 33, 101), (5, 34, 101),
                (6, 35, 101), (7, 36, 101), (8, 37, 102), (9, 38, 101), (10, 39, 101), (11, 31, 102)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers) VALUES
                (201, 32, 101, 'DRAFT', '{}' FORMAT JSON),
                (202, 33, 101, 'REVISION_REQUIRED', '{}' FORMAT JSON),
                (203, 34, 101, 'SUBMITTED', '{}' FORMAT JSON),
                (204, 35, 101, 'RESUBMITTED', '{}' FORMAT JSON),
                (205, 36, 101, 'CONFIRMED', '{}' FORMAT JSON),
                (206, 31, 102, 'REVISION_REQUIRED', '{}' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by, resolved_at) VALUES
                (301, 202, '현재 보완 사유', 10, NULL),
                (302, 202, '해결된 보완 사유', 10, '2026-11-04'),
                (303, 206, '다른 학생 보완 사유', 10, NULL),
                (304, 203, '이전 상태의 보완 사유', 10, NULL)
            """).update();
    }

    @Test
    void defaultsToTodoInDueOrderWithoutDuplicateTargets() {
        var tasks = repository.find(101, null, null);

        assertThat(tasks).extracting(TaskRow::requestId).containsExactly(31L, 32L, 33L);
        assertThat(tasks).extracting(TaskRow::status).containsExactly("NOT_SUBMITTED", "DRAFT", "REVISION_REQUIRED");
    }

    @Test
    void allIncludesOwnInProgressRequestsInNewestOrder() {
        var tasks = repository.find(101, Filter.ALL, Sort.CREATED_DESC);

        assertThat(tasks).extracting(TaskRow::requestId).containsExactly(36L, 35L, 34L, 33L, 32L, 31L);
        assertThat(tasks).extracting(TaskRow::status)
            .containsExactly("CONFIRMED", "RESUBMITTED", "SUBMITTED", "REVISION_REQUIRED", "DRAFT", "NOT_SUBMITTED");
    }

    @Test
    void returnsCommonDeadlineAndOnlyCurrentRevisionReason() {
        var tasks = repository.find(101, Filter.ALL, Sort.DUE_ASC);
        var revision = tasks.stream().filter(task -> task.requestId() == 33).findFirst().orElseThrow();

        assertThat(revision.title()).isEqualTo("요청3");
        assertThat(revision.requestDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 0, 0));
        assertThat(revision.revisionReason()).isEqualTo("현재 보완 사유");
        assertThat(tasks).filteredOn(task -> !task.status().equals("REVISION_REQUIRED"))
            .extracting(TaskRow::revisionReason).containsOnlyNulls();
    }

    @Test
    void keepsOtherScholarsTargetsAndSubmissionStatusSeparate() {
        var tasks = repository.find(102, Filter.TODO, Sort.DUE_ASC);

        assertThat(tasks).extracting(TaskRow::requestId).containsExactly(31L, 37L);
        assertThat(tasks).extracting(TaskRow::status).containsExactly("REVISION_REQUIRED", "NOT_SUBMITTED");
        assertThat(tasks.getFirst().revisionReason()).isEqualTo("다른 학생 보완 사유");
    }

    @Test
    void returnsNoTasksWithoutTargets() {
        assertThat(repository.find(999, Filter.ALL, Sort.DUE_ASC)).isEmpty();
    }
}
