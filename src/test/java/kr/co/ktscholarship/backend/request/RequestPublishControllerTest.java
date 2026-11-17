package kr.co.ktscholarship.backend.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RequestPublishControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    /*
     * 프로그램 851: 기수 856, 857 / 프로그램 852: 기수 858 / 프로그램 853: 기수 없음
     * 장학생
     *   951 기수 856 재학          ← 대상
     *   952 기수 856 휴학          (재학 아님)
     *   953 기수 857 재학          ← 프로그램 전체 요청일 때만 대상
     *   954 기수 856 재학, 계정 비활성
     *   955 기수 858 재학          (다른 프로그램)
     *   956 기수 856 재학, 탈퇴
     *   957 기수 856 재학, 계정 잠김 ← 대상 (잠금은 잠깐이라 포함)
     * 템플릿 860: 항목 3개 / 861: 항목 없음
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (851, '프로그램'), (852, '다른 프로그램'), (853, '빈 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (856, 851, '6기'), (857, 851, '7기'), (858, 852, '다른 6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role, status, deleted_at) VALUES
                (950, '담당자', 'publish-manager@example.test', 'test-only', 'MANAGER', 'ACTIVE', NULL),
                (951, '재학생', 'publish-1@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (952, '휴학생', 'publish-2@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (953, '7기생', 'publish-3@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (954, '비활성', 'publish-4@example.test', 'test-only', 'SCHOLAR', 'INACTIVE', NULL),
                (955, '다른 프로그램', 'publish-5@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (956, '탈퇴', 'publish-6@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', '2026-11-01 00:00:00'),
                (957, '잠김', 'publish-7@example.test', 'test-only', 'SCHOLAR', 'LOCKED', NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name, status) VALUES
                (951, 856, '학교', 'ENROLLED'), (952, 856, '학교', 'LEAVE'), (953, 857, '학교', 'ENROLLED'),
                (954, 856, '학교', 'ENROLLED'), (955, 858, '학교', 'ENROLLED'), (956, 856, '학교', 'ENROLLED'),
                (957, 856, '학교', 'ENROLLED')
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields) VALUES
                (860, 'TEST_PUBLISH', '성적증명서', :fields FORMAT JSON),
                (861, 'TEST_PUBLISH_EMPTY', '빈 템플릿', '[]' FORMAT JSON)
            """).param("fields", """
            [{"fieldKey":"gpa","label":"평점","fieldType":"NUMBER","required":true,
              "validation":{"min":0,"max":4.5},"helpText":"소수 둘째 자리까지"},
             {"fieldKey":"scale","label":"만점","fieldType":"SELECT_ONE","required":true,
              "options":[{"value":"4.5","label":"4.5"},{"value":"4.3","label":"4.3"}]},
             {"fieldKey":"transcript","label":"성적증명서","fieldType":"FILE","required":true,
              "validation":{"allowedExtensions":["pdf"],"maxSizeMb":10,"maxFiles":1}}]
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at) VALUES
                (851, 851, 856, 860, '6기 성적', 2026, '2', '2099-12-31 14:59:59', 'DRAFT', 950, NULL),
                (852, 851, NULL, 860, '전체 성적', 2026, '2', '2099-12-31 14:59:59', 'DRAFT', 950, NULL),
                (853, 851, NULL, 860, '이미 공개', 2026, '2', '2099-12-31 14:59:59', 'IN_PROGRESS', 950,
                    '2026-11-10 00:00:00'),
                (854, 851, NULL, 861, '항목 없는 템플릿', 2026, '2', '2099-12-31 14:59:59', 'DRAFT', 950, NULL),
                (855, 851, NULL, 860, '마감 지남', 2026, '2', '2020-01-01 00:00:00', 'DRAFT', 950, NULL),
                (856, 853, NULL, 860, '대상 없음', 2026, '2', '2099-12-31 14:59:59', 'DRAFT', 950, NULL)
            """).update();
    }

    @Test
    void publishesCohortRequestWithFieldsAndTargetSnapshot() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/851/publish")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.id").value(851))
            .andExpect(jsonPath("$.success.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.success.data.publishedAt").isString())
            .andExpect(jsonPath("$.success.data.targetCount").value(2))
            .andExpect(jsonPath("$.meta.path").value("/v1/requests/851/publish"));

        Map<String, Object> request = jdbc.sql("SELECT status, published_at, version FROM requests WHERE id = 851")
            .query().singleRow();
        assertThat(request.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(request.get("published_at")).isNotNull();
        assertThat(request.get("version")).isEqualTo(1);

        // 휴학·비활성·탈퇴·다른 기수·다른 프로그램은 빠지고, 재학생과 잠긴 계정만
        assertThat(targets(851)).containsExactly(951L, 957L);

        // 템플릿 항목이 순서대로 복사된다
        assertThat(jdbc.sql("SELECT field_key FROM request_fields WHERE request_id = 851 ORDER BY sort_order")
            .query(String.class).list()).containsExactly("gpa", "scale", "transcript");
    }

    @Test
    void publishesProgramWideRequestToAllCohorts() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/852/publish")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.targetCount").value(3));

        assertThat(targets(852)).containsExactly(951L, 953L, 957L);
    }

    // 공개된 폼을 대상 장학생이 그대로 받는다 (복사한 JSON이 문자열이 아니라 객체로 저장됐는지 함께 확인)
    @Test
    void targetScholarSeesCopiedFormAfterPublish() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/851/publish"))).andExpect(status().isOk());

        mockMvc.perform(get("/v1/me/submissions/851")
                .with(jwt().jwt(token -> token.subject("951")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.fields[*].fieldKey", contains("gpa", "scale", "transcript")))
            .andExpect(jsonPath("$.success.data.fields[0].required").value(true))
            .andExpect(jsonPath("$.success.data.fields[0].validation.max").value(4.5))
            .andExpect(jsonPath("$.success.data.fields[0].helpText").value("소수 둘째 자리까지"))
            .andExpect(jsonPath("$.success.data.fields[1].options[1].value").value("4.3"))
            .andExpect(jsonPath("$.success.data.fields[2].validation.allowedExtensions[0]").value("pdf"));
    }

    // 템플릿이 나중에 바뀌어도 이미 공개된 요청의 항목은 그대로다
    @Test
    void keepsPublishedFieldsWhenTemplateChangesLater() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/851/publish"))).andExpect(status().isOk());

        jdbc.sql("UPDATE request_templates SET default_fields = '[]' FORMAT JSON WHERE id = 860").update();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_fields WHERE request_id = 851")
            .query(Long.class).single()).isEqualTo(3L);
    }

    @ParameterizedTest
    @CsvSource({
        "853, status",
        "854, fields",
        "855, closesAt",
        "856, targets"
    })
    void rejectsUnpublishableRequestWithoutChangingAnything(long requestId, String reason) throws Exception {
        String before = jdbc.sql("SELECT status FROM requests WHERE id = :id").param("id", requestId)
            .query(String.class).single();

        mockMvc.perform(asManager(post("/v1/requests/" + requestId + "/publish")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_PUBLISHABLE"))
            .andExpect(jsonPath("$.error.details[0]").value(reason));

        // 실패하면 상태·항목·대상 모두 그대로 (중간에 넣은 항목도 되돌아감)
        assertThat(jdbc.sql("SELECT status FROM requests WHERE id = :id").param("id", requestId)
            .query(String.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_fields WHERE request_id = :id").param("id", requestId)
            .query(Long.class).single()).isZero();
        assertThat(targets(requestId)).isEmpty();
    }

    @Test
    void rejectsSecondPublish() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/851/publish"))).andExpect(status().isOk());

        mockMvc.perform(asManager(post("/v1/requests/851/publish")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0]").value("status"));

        assertThat(targets(851)).hasSize(2);
    }

    @Test
    void returnsNotFoundForMissingRequest() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests/999999/publish")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void rejectsScholarAndMissingToken() throws Exception {
        mockMvc.perform(post("/v1/requests/851/publish")
                .with(jwt().jwt(token -> token.subject("951")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/v1/requests/851/publish"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertThat(targets(851)).isEmpty();
    }

    private List<Long> targets(long requestId) {
        return jdbc.sql("SELECT scholar_id FROM request_targets WHERE request_id = :id ORDER BY scholar_id")
            .param("id", requestId).query(Long.class).list();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asManager(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().jwt(token -> token.subject("950")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
