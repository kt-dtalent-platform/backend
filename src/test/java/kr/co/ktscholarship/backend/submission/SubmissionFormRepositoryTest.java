package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionFormRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SubmissionFormRepository repository;

    @Autowired
    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        jdbc.sql("INSERT INTO programs (id, name) VALUES (1, '검증 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (6, 1, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (10, '담당자', 'manager@example.test', 'test-only', 'MANAGER'),
                (101, '장학생', 'scholar@example.test', 'test-only', 'SCHOLAR'),
                (102, '다른 장학생', 'other@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name) VALUES
                (101, 6, '검증 학교'), (102, 6, '검증 학교')
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (1, 'TEST_FORM', '검증용', '[{"fieldKey":"template_only"}]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests
                (id, program_id, template_id, title, description, academic_year, semester,
                closes_at, status, created_by, published_at)
            VALUES
                (31, 1, 1, '성적증명서 제출 검증', '검증 안내', 2026, '2', '2026-11-10', 'IN_PROGRESS', 10, '2026-11-01'),
                (32, 1, 1, '공개 전', NULL, 2026, '2', '2026-11-11', 'DRAFT', 10, NULL),
                (33, 1, 1, '마감 요청', NULL, 2026, '2', '2026-11-12', 'CLOSED', 10, '2026-11-01'),
                (34, 1, 1, '다른 대상', NULL, 2026, '2', '2026-11-13', 'IN_PROGRESS', 10, '2026-11-01')
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (id, request_id, scholar_id) VALUES
                (1, 31, 101), (2, 31, 102), (3, 32, 101), (4, 33, 101), (5, 34, 102)
            """).update();
        // test_number/test_file은 조회 검증용 키이며, 실제 MVP 템플릿 정의가 아닙니다.
        jdbc.sql("""
            INSERT INTO request_fields (id, request_id, field_key, label, field_type, required, validation, sort_order)
            VALUES
                (11, 31, 'test_file', '성적증명서(검증용)', 'FILE', TRUE, NULL, 20),
                (12, 31, 'test_number', '검증용 입력', 'NUMBER', TRUE, '{"min":0}' FORMAT JSON, 10),
                (13, 34, 'other_field', '다른 요청 항목', 'TEXT', FALSE, NULL, 0)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers, version, manual_review_reason) VALUES
                (201, 31, 101, 'REVISION_REQUIRED', '{"test_number":3.5,"test_flag":true}' FORMAT JSON, 4, 'staff-only'),
                (202, 31, 102, 'REVISION_REQUIRED', '{"test_number":99}' FORMAT JSON, 9, NULL),
                (203, 33, 101, 'CONFIRMED', '{}' FORMAT JSON, 5, NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO submission_files
                (id, submission_id, field_key, s3_key, original_name, content_type, size_bytes, upload_status)
            VALUES
                (301, 201, 'test_file', 'private-key-301', 'transcript.pdf', 'application/pdf', 100, 'UPLOADED'),
                (302, 201, 'test_file', 'private-key-302', 'pending.pdf', 'application/pdf', 100, 'PENDING'),
                (303, 201, 'test_file', 'private-key-303', 'deleted.pdf', 'application/pdf', 100, 'DELETED'),
                (304, 202, 'test_file', 'private-key-304', 'other.pdf', 'application/pdf', 100, 'UPLOADED')
            """).update();
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by, resolved_at) VALUES
                (401, 201, '성적증명서 파일 확인 필요', 10, NULL),
                (402, 201, '해결된 보완', 10, '2026-11-01'),
                (403, 202, '다른 학생 보완', 10, NULL),
                (404, 203, '이전 상태 보완', 10, NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO revision_request_fields (id, revision_request_id, field_key) VALUES
                (501, 401, 'test_file'), (502, 402, 'test_number'), (503, 403, 'other_field')
            """).update();
    }

    @Test
    void returnsRequestSnapshotAndOwnSubmissionData() {
        var data = repository.find(31, 101);

        assertThat(data.request().id()).isEqualTo(31);
        assertThat(data.request().title()).isEqualTo("성적증명서 제출 검증");
        assertThat(data.request().description()).isEqualTo("검증 안내");
        assertThat(data.request().requestDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 0, 0));
        assertThat(data.fields()).extracting(SubmissionFormData.Field::fieldKey)
            .containsExactly("test_number", "test_file");
        assertThat(data.fields().getFirst().validation()).containsEntry("min", 0);
        assertThat(data.fields().getLast().validation()).isNull();
        assertThat(data.submission().id()).isEqualTo(201);
        assertThat(data.submission().answers()).containsEntry("test_number", 3.5).containsEntry("test_flag", true);
        assertThat(data.submission().version()).isEqualTo(4);
        assertThat(data.files()).extracting(SubmissionFormData.File::id).containsExactlyInAnyOrder(301L, 302L, 303L);
        assertThat(data.files()).extracting(SubmissionFormData.File::uploadStatus)
            .containsExactlyInAnyOrder("UPLOADED", "PENDING", "DELETED");
        assertThat(data.revision().reason()).isEqualTo("성적증명서 파일 확인 필요");
        assertThat(data.revision().fieldKeys()).containsExactly("test_file");
        assertThat(mapper.writeValueAsString(data)).doesNotContain("private-key", "staff-only", "internalMemo");
    }

    @Test
    void keepsStudentsSubmissionsFilesAndRevisionsSeparate() {
        var data = repository.find(31, 102);

        assertThat(data.submission().id()).isEqualTo(202);
        assertThat(data.submission().answers()).containsEntry("test_number", 99);
        assertThat(data.files()).extracting(SubmissionFormData.File::id).containsExactly(304L);
        assertThat(data.revision().reason()).isEqualTo("다른 학생 보완");
        assertThat(data.revision().fieldKeys()).containsExactly("other_field");
    }

    @Test
    void keepsFirstVisitSubmissionAbsentWithoutInventingStatusOrVersion() {
        var data = repository.find(34, 102);

        assertThat(data.submission()).isNull();
        assertThat(data.files()).isEmpty();
        assertThat(data.revision()).isNull();
    }

    @Test
    void allowsClosedRequestReadAndOmitsRevisionAfterConfirmation() {
        var data = repository.find(33, 101);

        assertThat(data.request().status()).isEqualTo("CLOSED");
        assertThat(data.request().description()).isNull();
        assertThat(data.submission().status()).isEqualTo("CONFIRMED");
        assertThat(data.revision()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {32, 999})
    void rejectsUnpublishedOrMissingRequests(long requestId) {
        assertThatThrownBy(() -> repository.find(requestId, 101))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_NOT_FOUND);
                assertThat(error.getErrorCode().status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void rejectsRequestOutsideScholarTargets() {
        assertThatThrownBy(() -> repository.find(34, 101))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_A_TARGET);
                assertThat(error.getErrorCode().status()).isEqualTo(HttpStatus.FORBIDDEN);
            });
    }

    @ParameterizedTest
    @ValueSource(longs = {31, 33})
    void rejectsRequestsWithoutPublicationTimestamp(long requestId) {
        jdbc.sql("UPDATE requests SET published_at = NULL WHERE id = :id").param("id", requestId).update();

        assertThatThrownBy(() -> repository.find(requestId, 101))
            .isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_NOT_FOUND));
    }

    @Test
    void rejectsAmbiguousDuplicateSubmissions() {
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers)
            VALUES (204, 31, 101, 'DRAFT', '{}' FORMAT JSON)
            """).update();

        assertThatThrownBy(() -> repository.find(31, 101)).isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }

    @Test
    void rejectsAmbiguousOpenRevisions() {
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by)
            VALUES (405, 201, '중복 보완 요청', 10)
            """).update();

        assertThatThrownBy(() -> repository.find(31, 101)).isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }
}
