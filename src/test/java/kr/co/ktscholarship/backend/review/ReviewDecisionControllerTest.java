package kr.co.ktscholarship.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class ReviewDecisionControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    /*
     * 요청 891 (진행 중, 마감 2099), 항목 gpa(필수 숫자)·memo(선택 글)
     * 제출 8911 제출(v1) · 8912 재제출(v3) · 8913 확인 완료 · 8914 보완 필요 · 8915 작성 중
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (891, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (896, 891, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (990, '담당자', 'decision-manager@example.test', 'test-only', 'MANAGER'),
                (991, '장학생1', 'decision-1@example.test', 'test-only', 'SCHOLAR'),
                (992, '장학생2', 'decision-2@example.test', 'test-only', 'SCHOLAR'),
                (993, '장학생3', 'decision-3@example.test', 'test-only', 'SCHOLAR'),
                (994, '장학생4', 'decision-4@example.test', 'test-only', 'SCHOLAR'),
                (995, '장학생5', 'decision-5@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name)
            SELECT id, 896, '학교' FROM users WHERE id BETWEEN 991 AND 995
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (898, 'TEST_DECISION', '성적증명서', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at) VALUES
                (891, 891, 896, 898, '2학기 성적', 2026, '2', '2099-12-31 14:59:59', 'IN_PROGRESS', 990,
                    '2026-11-01 00:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO request_fields (request_id, field_key, label, field_type, required, sort_order) VALUES
                (891, 'gpa', '학기 평점', 'NUMBER', TRUE, 1),
                (891, 'memo', '메모', 'TEXT', FALSE, 2)
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (request_id, scholar_id)
            SELECT 891, id FROM users WHERE id BETWEEN 991 AND 995
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers, submitted_at, version) VALUES
                (8911, 891, 991, 'SUBMITTED', '{"gpa":3.9,"memo":"처음"}' FORMAT JSON, '2026-11-10 00:00:00', 1),
                (8912, 891, 992, 'RESUBMITTED', '{"gpa":3.5}' FORMAT JSON, '2026-11-09 00:00:00', 3),
                (8913, 891, 993, 'CONFIRMED', '{"gpa":4.0}' FORMAT JSON, '2026-11-08 00:00:00', 2),
                (8914, 891, 994, 'REVISION_REQUIRED', '{"gpa":3.0}' FORMAT JSON, '2026-11-08 00:00:00', 2),
                (8915, 891, 995, 'DRAFT', '{"gpa":2.0}' FORMAT JSON, NULL, 0)
            """).update();
    }

    // ---------- 확인 완료 ----------

    @Test
    void confirmsSubmittedAndRecordsWhoAndHistory() throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8911/confirm")).content("{\"version\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.id").value(8911))
            .andExpect(jsonPath("$.success.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.success.data.version").value(2))
            .andExpect(jsonPath("$.meta.path").value("/v1/submissions/8911/confirm"));

        Map<String, Object> row = submission(8911);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("version")).isEqualTo(2);
        assertThat(row.get("confirmed_by")).isEqualTo(990L);
        assertThat(row.get("confirmed_at")).isNotNull();

        Map<String, Object> history = jdbc.sql("""
            SELECT from_status, to_status, actor_type, actor_id, CAST(snapshot AS VARCHAR(1000)) AS snapshot
            FROM submission_histories WHERE submission_id = 8911
            """).query().singleRow();
        assertThat(history).containsEntry("from_status", "SUBMITTED").containsEntry("to_status", "CONFIRMED")
            .containsEntry("actor_type", "MANAGER").containsEntry("actor_id", 990L);
        assertThat((String) history.get("snapshot")).contains("\"answers\"").contains("처음").contains("\"files\"");
    }

    @Test
    void confirmsResubmitted() throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8912/confirm")).content("{\"version\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.version").value(4));
    }

    // ---------- 보완 요청 ----------

    @Test
    void requestsRevisionForChosenFields() throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점이 증명서와 달라요","dueAt":"2099-12-01T14:59:59Z","version":1}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.status").value("REVISION_REQUIRED"))
            .andExpect(jsonPath("$.success.data.version").value(2));

        Map<String, Object> revision = jdbc.sql("""
            SELECT id, reason, requested_by, resolved_at FROM revision_requests WHERE submission_id = 8911
            """).query().singleRow();
        assertThat(revision).containsEntry("reason", "평점이 증명서와 달라요").containsEntry("requested_by", 990L);
        assertThat(revision.get("resolved_at")).isNull();
        assertThat(jdbc.sql("SELECT field_key FROM revision_request_fields WHERE revision_request_id = :id")
            .param("id", revision.get("id")).query(String.class).list()).containsExactly("gpa");
        assertThat(submission(8911).get("status")).isEqualTo("REVISION_REQUIRED");
        assertThat(jdbc.sql("SELECT to_status FROM submission_histories WHERE submission_id = 8911")
            .query(String.class).single()).isEqualTo("REVISION_REQUIRED");
        // 공통 마감(2099-12-31)보다 이른 보완 기한이라 마감은 그대로
        assertThat(extendedDueAt(991)).isNull();
    }

    // 보완 요청 → 장학생이 그 항목만 고쳐 재제출 (정윤 재제출 API와 연결)
    @Test
    void scholarCanResubmitOnlyRequestedFieldAfterRevision() throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점 다시","dueAt":null,"version":1}
                """))
            .andExpect(status().isOk());

        // 요청되지 않은 항목(memo)을 고치면 거절
        mockMvc.perform(asScholar991(post("/v1/me/submissions/891/resubmit")).content("""
                {"answers":{"memo":"바꿈"},"version":2}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("FIELD_NOT_EDITABLE"));

        mockMvc.perform(asScholar991(post("/v1/me/submissions/891/resubmit")).content("""
                {"answers":{"gpa":3.7},"version":2}
                """))
            .andExpect(status().isOk());

        Map<String, Object> row = submission(8911);
        assertThat(row.get("status")).isEqualTo("RESUBMITTED");
        assertThat(jdbc.sql("SELECT resolved_at FROM revision_requests WHERE submission_id = 8911")
            .query().singleRow().get("resolved_at")).isNotNull();

        // 다시 검토할 수 있다
        mockMvc.perform(asManager(post("/v1/submissions/8911/confirm"))
                .content("{\"version\":" + row.get("version") + "}"))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"fieldKeys\":[],\"reason\":\"사유\",\"version\":1}                          | fieldKeys",
        "{\"fieldKeys\":[\"nope\"],\"reason\":\"사유\",\"version\":1}                  | fieldKeys",
        "{\"fieldKeys\":[\"gpa\",\"gpa\"],\"reason\":\"사유\",\"version\":1}           | fieldKeys",
        "{\"fieldKeys\":[\"gpa\"],\"reason\":\"  \",\"version\":1}                     | reason",
        "{\"fieldKeys\":[\"gpa\"],\"reason\":\"사유\",\"dueAt\":\"2020-01-01T00:00:00Z\",\"version\":1} | dueAt",
        "{\"fieldKeys\":[\"gpa\"],\"reason\":\"사유\"}                                 | version"
    })
    void rejectsInvalidRevisionBodyWithoutChanges(String body, String field) throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details", hasItem(field)));

        assertUnchanged(8911, "SUBMITTED", 1);
    }

    // ---------- 보완 기한 → 장학생별 재제출 마감 ----------

    // 마감이 지난 뒤 보완 기한을 주면 그 장학생의 마감이 되어 다시 낼 수 있다
    @Test
    void dueAtBecomesScholarDeadlineAfterCommonDeadlinePassed() throws Exception {
        closeRequest();

        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점 다시","dueAt":"2099-01-31T14:59:59.900Z","version":1}
                """))
            .andExpect(status().isOk());

        assertThat(extendedDueAt(991)).isEqualTo(LocalDateTime.parse("2099-01-31T14:59:59"));
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM audit_logs WHERE action = 'EXTEND_DEADLINE' AND actor_id = 990
                AND target_id = (SELECT id FROM request_targets WHERE request_id = 891 AND scholar_id = 991)
            """).query(Long.class).single()).isEqualTo(1L);

        mockMvc.perform(asScholar991(post("/v1/me/submissions/891/resubmit")).content("""
                {"answers":{"gpa":3.7},"version":2}
                """))
            .andExpect(status().isOk());
        assertThat(submission(8911).get("status")).isEqualTo("RESUBMITTED");
    }

    // 이미 더 늦게 연장받은 장학생이면 보완 기한이 마감을 줄이지 않는다
    @Test
    void earlierDueAtDoesNotShortenExtendedDeadline() throws Exception {
        jdbc.sql("UPDATE request_targets SET extended_due_at = '2099-12-31 14:59:59' WHERE scholar_id = 991").update();
        jdbc.sql("UPDATE requests SET closes_at = '2099-06-30 14:59:59' WHERE id = 891").update();

        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점 다시","dueAt":"2099-07-31T14:59:59Z","version":1}
                """))
            .andExpect(status().isOk());

        assertThat(extendedDueAt(991)).isEqualTo(LocalDateTime.parse("2099-12-31T14:59:59"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'EXTEND_DEADLINE'")
            .query(Long.class).single()).isZero();
    }

    // 마감이 지났는데 보완 기한이 없으면 장학생이 낼 수 없는 보완 요청이라 거절한다
    @Test
    void rejectsMissingDueAtAfterDeadlineWithoutChanges() throws Exception {
        closeRequest();

        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점 다시","version":1}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details", hasItem("dueAt")));

        assertUnchanged(8911, "SUBMITTED", 1);
        assertThat(extendedDueAt(991)).isNull();
    }

    // 연장 마감이 아직 남아 있으면 공통 마감이 지났어도 보완 기한 없이 보낼 수 있다
    @Test
    void allowsMissingDueAtWhileExtendedDeadlineRemains() throws Exception {
        closeRequest();
        jdbc.sql("UPDATE request_targets SET extended_due_at = '2099-01-31 14:59:59' WHERE scholar_id = 991").update();

        mockMvc.perform(asManager(post("/v1/submissions/8911/revision")).content("""
                {"fieldKeys":["gpa"],"reason":"평점 다시","version":1}
                """))
            .andExpect(status().isOk());

        assertThat(extendedDueAt(991)).isEqualTo(LocalDateTime.parse("2099-01-31T14:59:59"));
    }

    // ---------- 공통: 상태·version·없음 ----------

    @ParameterizedTest
    @CsvSource({
        "8913, 2, confirm",
        "8914, 2, confirm",
        "8913, 2, revision",
        "8914, 2, revision"
    })
    void rejectsNotReviewableState(long id, int version, String action) throws Exception {
        String before = (String) submission(id).get("status");
        mockMvc.perform(asManager(post("/v1/submissions/" + id + "/" + action)).content(body(action, version)))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_REVIEWABLE"));

        assertUnchanged(id, before, version);
    }

    @ParameterizedTest
    @CsvSource({"confirm", "revision"})
    void rejectsStaleVersion(String action) throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/8911/" + action)).content(body(action, 0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        assertUnchanged(8911, "SUBMITTED", 1);
    }

    @ParameterizedTest
    @CsvSource({"8915, confirm", "8915, revision", "999999, confirm", "999999, revision"})
    void hidesDraftAndMissingSubmission(long id, String action) throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/" + id + "/" + action)).content(body(action, 0)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_FOUND"));
    }

    @Test
    void rejectsScholarAndMissingToken() throws Exception {
        for (String action : List.of("confirm", "revision")) {
            mockMvc.perform(asScholar991(post("/v1/submissions/8911/" + action)).content(body(action, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mockMvc.perform(post("/v1/submissions/8911/" + action).contentType(MediaType.APPLICATION_JSON)
                    .content(body(action, 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        assertUnchanged(8911, "SUBMITTED", 1);
    }

    // ---------- 도움 메서드 ----------

    private String body(String action, int version) {
        return action.equals("confirm")
            ? "{\"version\":" + version + "}"
            : "{\"fieldKeys\":[\"gpa\"],\"reason\":\"사유\",\"version\":" + version + "}";
    }

    private void assertUnchanged(long id, String status, int version) {
        Map<String, Object> row = submission(id);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("version")).isEqualTo(version);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission_histories WHERE submission_id = :id").param("id", id)
            .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM revision_requests WHERE submission_id = :id").param("id", id)
            .query(Long.class).single()).isZero();
    }

    private void closeRequest() {
        jdbc.sql("UPDATE requests SET closes_at = '2026-01-01 00:00:00' WHERE id = 891").update();
    }

    private LocalDateTime extendedDueAt(long scholarId) {
        return jdbc.sql("SELECT extended_due_at FROM request_targets WHERE request_id = 891 AND scholar_id = :scholar")
            .param("scholar", scholarId).query(LocalDateTime.class).optional().orElse(null);
    }

    private Map<String, Object> submission(long id) {
        return jdbc.sql("SELECT * FROM submissions WHERE id = :id").param("id", id).query().singleRow();
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON)
            .with(jwt().jwt(token -> token.subject("990")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    private MockHttpServletRequestBuilder asScholar991(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON)
            .with(jwt().jwt(token -> token.subject("991")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")));
    }
}
