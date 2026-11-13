package kr.co.ktscholarship.backend.file;

import static org.assertj.core.api.Assertions.assertThat;

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
class FilePresignRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private FilePresignRepository repository;

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
            VALUES (1, 'TEST_FORM', '검증용', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests
                (id, program_id, template_id, title, academic_year, semester, closes_at, created_by)
            VALUES (31, 1, 1, '검증 요청', 2026, '2', '2026-11-10', 10),
                (32, 1, 1, '다른 요청', 2026, '2', '2026-11-10', 10)
            """).update();
        // validation 키의 정책은 정하지 않고 저장된 JSON을 보존하는지만 검증합니다.
        jdbc.sql("""
            INSERT INTO request_fields (id, request_id, field_key, label, field_type, validation) VALUES
                (11, 31, 'receipt', '영수증', 'FILE', '{"testRule":["pdf"]}' FORMAT JSON),
                (12, 31, 'text_field', '입력값', 'TEXT', NULL),
                (13, 32, 'other_field', '다른 요청 파일', 'FILE', NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers) VALUES
                (201, 31, 101, 'REVISION_REQUIRED', '{}' FORMAT JSON),
                (202, 31, 102, 'DRAFT', '{}' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO submission_files
                (id, submission_id, field_key, s3_key, original_name, content_type, size_bytes, upload_status)
            VALUES
                (301, 201, 'receipt', 'test/301', 'a.pdf', 'application/pdf', 100, 'PENDING'),
                (302, 201, 'receipt', 'test/302', 'b.pdf', 'application/pdf', 100, 'UPLOADED'),
                (303, 201, 'receipt', 'test/303', 'c.pdf', 'application/pdf', 100, 'DELETED'),
                (304, 202, 'receipt', 'test/304', 'd.pdf', 'application/pdf', 100, 'UPLOADED'),
                (305, 201, 'other_field', 'test/305', 'e.pdf', 'application/pdf', 100, 'UPLOADED')
            """).update();
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by, resolved_at) VALUES
                (401, 201, '현재 보완', 10, NULL),
                (402, 201, '해결된 보완', 10, '2026-11-01'),
                (403, 202, '다른 학생 보완', 10, NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO revision_request_fields (id, revision_request_id, field_key) VALUES
                (501, 401, 'receipt'), (502, 402, 'resolved_field'), (503, 403, 'other_field')
            """).update();
    }

    @Test
    void returnsSubmissionOwnerRequestAndStatus() {
        var submission = repository.findSubmission(201).orElseThrow();

        assertThat(submission.scholarId()).isEqualTo(101);
        assertThat(submission.requestId()).isEqualTo(31);
        assertThat(submission.status()).isEqualTo("REVISION_REQUIRED");
        assertThat(repository.findSubmission(999)).isEmpty();
    }

    @Test
    void scopesFieldsToRequestAndPreservesStoredValidation() {
        var field = repository.findField(31, "receipt").orElseThrow();

        assertThat(field.fieldType()).isEqualTo("FILE");
        assertThat(field.validation()).containsKey("testRule");
        assertThat(repository.findField(31, "other_field")).isEmpty();
        assertThat(repository.findField(31, "missing")).isEmpty();
        assertThat(repository.findField(31, "text_field").orElseThrow().validation()).isNull();
    }

    @Test
    void countsPendingAndUploadedFilesWithinSameSubmissionAndField() {
        assertThat(repository.countActiveFiles(201, "receipt")).isEqualTo(2);
        assertThat(repository.countActiveFiles(202, "receipt")).isEqualTo(1);
        assertThat(repository.countActiveFiles(201, "missing")).isZero();
    }

    @Test
    void acceptsOnlyUnresolvedRevisionFieldsOfSameSubmission() {
        assertThat(repository.isRevisionField(201, "receipt")).isTrue();
        assertThat(repository.isRevisionField(201, "resolved_field")).isFalse();
        assertThat(repository.isRevisionField(201, "other_field")).isFalse();
        assertThat(repository.isRevisionField(201, "missing")).isFalse();
    }

    @Test
    void onlyLatestUnresolvedRevisionCanAllowAnUpload() {
        jdbc.sql("UPDATE revision_requests SET requested_at = '2026-11-01' WHERE id = 401").update();
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by, requested_at, resolved_at) VALUES
                (404,201,'최신 미해결',10,'2026-11-03',NULL),
                (405,201,'해결된 최신',10,'2026-11-04','2026-11-05'),
                (406,201,'ID만 큰 과거',10,'2026-11-02',NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO revision_request_fields (revision_request_id, field_key) VALUES
                (404,'new_file'), (405,'receipt'), (406,'receipt')
            """).update();

        assertThat(repository.isRevisionField(201, "receipt")).isFalse();
        assertThat(repository.isRevisionField(201, "new_file")).isTrue();

        jdbc.sql("UPDATE revision_requests SET requested_at = '2026-11-03' WHERE id = 406").update();
        assertThat(repository.isRevisionField(201, "receipt")).isTrue();
        assertThat(repository.isRevisionField(201, "new_file")).isFalse();
    }

    @Test
    void rejectsRevisionFieldsWhenAllRequestsAreResolved() {
        jdbc.sql("UPDATE revision_requests SET resolved_at = '2026-11-06' WHERE submission_id = 201").update();
        assertThat(repository.isRevisionField(201, "receipt")).isFalse();
    }
}
