package kr.co.ktscholarship.backend.review;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionReviewControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    /*
     * 요청 871 (기수 876), 대상 7명
     *   971 가영  제출       11/10 제출          ← 검토 대기, 가장 오래 기다림
     *   972 나영  재제출     11/05 첫 제출, 11/12 재제출
     *   973 다영  제출       11/11 제출, 지각
     *   974 라영  보완 필요
     *   975 마영  확인 완료
     *   976 바영  작성 중     ← 담당자에게는 미제출
     *   977 사영  제출 없음
     * 대상이 아닌 978의 제출은 현황에 나오지 않음
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (871, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (876, 871, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (970, '담당자', 'review-manager@example.test', 'test-only', 'MANAGER'),
                (971, '가영', 'review-1@example.test', 'test-only', 'SCHOLAR'),
                (972, '나영', 'review-2@example.test', 'test-only', 'SCHOLAR'),
                (973, '다영', 'review-3@example.test', 'test-only', 'SCHOLAR'),
                (974, '라영', 'review-4@example.test', 'test-only', 'SCHOLAR'),
                (975, '마영', 'review-5@example.test', 'test-only', 'SCHOLAR'),
                (976, '바영', 'review-6@example.test', 'test-only', 'SCHOLAR'),
                (977, '사영', 'review-7@example.test', 'test-only', 'SCHOLAR'),
                (978, '대상 아님', 'review-8@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name)
            SELECT id, 876, '한국대학교' FROM users WHERE id BETWEEN 971 AND 978
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (880, 'TEST_REVIEW', '성적증명서', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at) VALUES
                (871, 871, 876, 880, '2학기 성적', 2026, '2', '2026-11-11 14:59:59', 'IN_PROGRESS', 970,
                    '2026-11-01 00:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO request_fields (request_id, field_key, label, field_type, required, sort_order) VALUES
                (871, 'gpa', '학기 평점', 'NUMBER', TRUE, 1),
                (871, 'gpa_scale', '만점 기준', 'SELECT_ONE', TRUE, 2),
                (871, 'transcript', '성적증명서', 'FILE', TRUE, 3)
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (request_id, scholar_id)
            SELECT 871, id FROM users WHERE id BETWEEN 971 AND 977
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers, is_late, submitted_at, updated_at, version)
            VALUES
                (8701, 871, 971, 'SUBMITTED', :a1 FORMAT JSON, FALSE, '2026-11-10 01:00:00', '2026-11-10 01:00:00', 1),
                (8702, 871, 972, 'RESUBMITTED', :a2 FORMAT JSON, FALSE, '2026-11-05 01:00:00', '2026-11-12 01:00:00', 3),
                (8703, 871, 973, 'SUBMITTED', :a3 FORMAT JSON, TRUE, '2026-11-11 15:30:00', '2026-11-11 15:30:00', 1),
                (8704, 871, 974, 'REVISION_REQUIRED', '{"gpa":3.1}' FORMAT JSON, FALSE, '2026-11-09 01:00:00',
                    '2026-11-13 01:00:00', 2),
                (8705, 871, 975, 'CONFIRMED', '{"gpa":4.0}' FORMAT JSON, FALSE, '2026-11-08 01:00:00',
                    '2026-11-14 01:00:00', 2),
                (8706, 871, 976, 'DRAFT', '{"gpa":2.0}' FORMAT JSON, FALSE, NULL, '2026-11-09 01:00:00', 0),
                (8708, 871, 978, 'SUBMITTED', '{}' FORMAT JSON, FALSE, '2026-11-09 01:00:00', '2026-11-09 01:00:00', 1)
            """)
            .param("a1", "{\"gpa\":3.85,\"gpa_scale\":\"4.5\"}")
            .param("a2", "{\"gpa\":3.5,\"gpa_scale\":\"4.3\"}")
            .param("a3", "{\"gpa\":\"모름\"}")
            .update();
    }

    // ---------- 제출 현황 ----------

    @Test
    void listsAllTargetsInReviewOrderWithSummary() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/871/submissions")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.request.title").value("2학기 성적"))
            .andExpect(jsonPath("$.success.data.request.cohortName").value("6기"))
            .andExpect(jsonPath("$.success.data.request.closesAt").value("2026-11-11T14:59:59Z"))
            // 대상 7명: 검토 대기 3, 보완 필요 1, 확인 완료 1, 미제출 2(작성 중 포함)
            .andExpect(jsonPath("$.success.data.summary.target").value(7))
            .andExpect(jsonPath("$.success.data.summary.submitted").value(3))
            .andExpect(jsonPath("$.success.data.summary.revisionRequired").value(1))
            .andExpect(jsonPath("$.success.data.summary.confirmed").value(1))
            .andExpect(jsonPath("$.success.data.summary.notSubmitted").value(2))
            .andExpect(jsonPath("$.success.data.total").value(7))
            // 검토 대기(가영 11/10, 다영 11/11, 나영 재제출 11/12) → 보완 → 미제출(이름순) → 확인
            .andExpect(jsonPath("$.success.data.items[*].scholarName",
                contains("가영", "다영", "나영", "라영", "바영", "사영", "마영")));
    }

    @Test
    void showsRowFieldsAndHidesDraft() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/871/submissions")))
            // 가영: 평점·만점은 답변에서
            .andExpect(jsonPath("$.success.data.items[0].scholarId").value(971))
            .andExpect(jsonPath("$.success.data.items[0].cohortName").value("6기"))
            .andExpect(jsonPath("$.success.data.items[0].university").value("한국대학교"))
            .andExpect(jsonPath("$.success.data.items[0].gpa").value(3.85))
            .andExpect(jsonPath("$.success.data.items[0].gpaScale").value("4.5"))
            .andExpect(jsonPath("$.success.data.items[0].submissionId").value(8701))
            .andExpect(jsonPath("$.success.data.items[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.success.data.items[0].submittedAt").value("2026-11-10T01:00:00Z"))
            .andExpect(jsonPath("$.success.data.items[0].late").value(false))
            .andExpect(jsonPath("$.success.data.items[0].hasInputError").value(false))
            // 다영: 지각, 평점이 숫자가 아니면 null
            .andExpect(jsonPath("$.success.data.items[1].late").value(true))
            .andExpect(jsonPath("$.success.data.items[1].gpa").doesNotExist())
            // 바영: 작성 중은 미제출, 제출 id·평점·제출 시각을 숨김
            .andExpect(jsonPath("$.success.data.items[4].scholarName").value("바영"))
            .andExpect(jsonPath("$.success.data.items[4].status").value("NOT_SUBMITTED"))
            .andExpect(jsonPath("$.success.data.items[4].submissionId").doesNotExist())
            .andExpect(jsonPath("$.success.data.items[4].gpa").doesNotExist())
            // 사영: 제출 행 없음
            .andExpect(jsonPath("$.success.data.items[5].status").value("NOT_SUBMITTED"));
    }

    @ParameterizedTest
    @CsvSource({
        "WAITING, 3, 가영",
        "REVISION_REQUIRED, 1, 라영",
        "NOT_SUBMITTED, 2, 바영",
        "CONFIRMED, 1, 마영"
    })
    void filtersByStatusChipButKeepsWholeSummary(String chip, int total, String first) throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/871/submissions").param("status", chip)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(total))
            .andExpect(jsonPath("$.success.data.items", hasSize(total)))
            .andExpect(jsonPath("$.success.data.items[0].scholarName").value(first))
            .andExpect(jsonPath("$.success.data.summary.target").value(7));
    }

    @Test
    void searchesByNameAndPages() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/871/submissions").param("keyword", " 나 ")))
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[*].scholarName", contains("나영")));

        // 검색어의 %는 글자 그대로
        mockMvc.perform(asManager(get("/v1/requests/871/submissions").param("keyword", "%")))
            .andExpect(jsonPath("$.success.data.total").value(0));

        mockMvc.perform(asManager(get("/v1/requests/871/submissions").param("page", "2").param("page_size", "3")))
            .andExpect(jsonPath("$.success.data.page").value(2))
            .andExpect(jsonPath("$.success.data.pageSize").value(3))
            .andExpect(jsonPath("$.success.data.total").value(7))
            .andExpect(jsonPath("$.success.data.items[*].scholarName", contains("라영", "바영", "사영")));
    }

    @ParameterizedTest
    @CsvSource({"status, SUBMITTED", "status, DRAFT", "page, 0", "page_size, 101"})
    void rejectsInvalidParameter(String name, String value) throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/871/submissions").param(name, value)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForMissingRequest() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/999999/submissions")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    // ---------- 제출 상세 ----------

    @Test
    void returnsSubmissionDetailForReview() throws Exception {
        jdbc.sql("""
            INSERT INTO submission_files (id, submission_id, field_key, s3_key, original_name, content_type,
                size_bytes, upload_status) VALUES
                (8801, 8702, 'transcript', 'submissions/8702/a.pdf', '성적증명서.pdf', 'application/pdf', 2048, 'UPLOADED'),
                (8802, 8702, 'transcript', 'submissions/8702/b.pdf', '올리다만.pdf', 'application/pdf', 10, 'PENDING'),
                (8803, 8702, 'transcript', 'submissions/8702/c.pdf', '지운.pdf', 'application/pdf', 10, 'DELETED')
            """).update();
        jdbc.sql("""
            INSERT INTO submission_histories (submission_id, from_status, to_status, actor_type, actor_id, snapshot,
                changed_at) VALUES
                (8702, 'DRAFT', 'SUBMITTED', 'SCHOLAR', 972, '{}' FORMAT JSON, '2026-11-05 01:00:00'),
                (8702, 'SUBMITTED', 'REVISION_REQUIRED', 'MANAGER', 970, '{}' FORMAT JSON, '2026-11-06 01:00:00'),
                (8702, 'REVISION_REQUIRED', 'RESUBMITTED', 'SCHOLAR', 972, '{}' FORMAT JSON, '2026-11-12 01:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO revision_requests (id, submission_id, reason, requested_by, requested_at, resolved_at) VALUES
                (8901, 8702, '평점이 증명서와 달라요', 970, '2026-11-06 01:00:00', '2026-11-12 01:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO revision_request_fields (revision_request_id, field_key) VALUES (8901, 'gpa'), (8901, 'transcript')
            """).update();

        mockMvc.perform(asManager(get("/v1/submissions/8702")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.id").value(8702))
            .andExpect(jsonPath("$.success.data.requestId").value(871))
            .andExpect(jsonPath("$.success.data.requestTitle").value("2학기 성적"))
            .andExpect(jsonPath("$.success.data.scholar.id").value(972))
            .andExpect(jsonPath("$.success.data.scholar.name").value("나영"))
            .andExpect(jsonPath("$.success.data.scholar.cohortName").value("6기"))
            .andExpect(jsonPath("$.success.data.status").value("RESUBMITTED"))
            .andExpect(jsonPath("$.success.data.submittedAt").value("2026-11-05T01:00:00Z"))
            .andExpect(jsonPath("$.success.data.late").value(false))
            .andExpect(jsonPath("$.success.data.fields[*].fieldKey", contains("gpa", "gpa_scale", "transcript")))
            .andExpect(jsonPath("$.success.data.answers.gpa").value(3.5))
            .andExpect(jsonPath("$.success.data.answers.gpa_scale").value("4.3"))
            // 업로드가 끝난 파일만
            .andExpect(jsonPath("$.success.data.files[*].id", contains(8801)))
            .andExpect(jsonPath("$.success.data.files[0].originalName").value("성적증명서.pdf"))
            .andExpect(jsonPath("$.success.data.files[0].contentType").value("application/pdf"))
            .andExpect(jsonPath("$.success.data.files[0].sizeBytes").value(2048))
            .andExpect(jsonPath("$.success.data.analysis").doesNotExist())
            .andExpect(jsonPath("$.success.data.history[*].to", contains("SUBMITTED", "REVISION_REQUIRED", "RESUBMITTED")))
            .andExpect(jsonPath("$.success.data.history[1].actorType").value("MANAGER"))
            .andExpect(jsonPath("$.success.data.history[0].changedAt").value("2026-11-05T01:00:00Z"))
            .andExpect(jsonPath("$.success.data.revisions[0].reason").value("평점이 증명서와 달라요"))
            .andExpect(jsonPath("$.success.data.revisions[0].fieldKeys", contains("gpa", "transcript")))
            .andExpect(jsonPath("$.success.data.revisions[0].resolvedAt").value("2026-11-12T01:00:00Z"))
            .andExpect(jsonPath("$.success.data.version").value(3))
            .andExpect(jsonPath("$.meta.path").value("/v1/submissions/8702"));
    }

    @Test
    void returnsEmptyListsWhenNothingAttached() throws Exception {
        mockMvc.perform(asManager(get("/v1/submissions/8701")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.files", hasSize(0)))
            .andExpect(jsonPath("$.success.data.history", hasSize(0)))
            .andExpect(jsonPath("$.success.data.revisions", hasSize(0)));
    }

    @Test
    void hidesDraftAndMissingSubmission() throws Exception {
        mockMvc.perform(asManager(get("/v1/submissions/8706")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_FOUND"));
        mockMvc.perform(asManager(get("/v1/submissions/999999")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_FOUND"));
    }

    // ---------- 권한 ----------

    @Test
    void rejectsScholarAndMissingToken() throws Exception {
        for (String path : new String[] {"/v1/requests/871/submissions", "/v1/submissions/8701"}) {
            mockMvc.perform(get(path).with(jwt().jwt(token -> token.subject("971"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().jwt(token -> token.subject("970")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
