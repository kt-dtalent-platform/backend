package kr.co.ktscholarship.backend.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
class RequestSaveControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    // 프로그램 801(기수 806) / 다른 프로그램 802(기수 807) / 템플릿 820 사용 중, 821 사용 안 함
    // 요청 801: 작성 중, 요청 802: 진행 중
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (801, '프로그램'), (802, '다른 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (806, 801, '6기'), (807, 802, '다른 6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role)
            VALUES (890, '담당자', 'save-manager@example.test', 'test-only', 'MANAGER')
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields, is_active) VALUES
                (820, 'TEST_SAVE', '성적증명서', '[]' FORMAT JSON, TRUE),
                (821, 'TEST_SAVE_OFF', '쓰지 않는 템플릿', '[]' FORMAT JSON, FALSE)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at) VALUES
                (801, 801, NULL, 820, '고치기 전 제목', 2026, '2', '2099-12-23 14:59:00', 'DRAFT', 890, NULL),
                (802, 801, 806, 820, '공개된 요청', 2026, '1', '2099-11-20 14:59:59', 'IN_PROGRESS', 890,
                    '2026-11-10 01:00:00')
            """).update();
    }

    // ---------- 만들기 ----------

    @Test
    void createsDraftRequest() throws Exception {
        var result = mockMvc.perform(asManager(post("/v1/requests")).content(body(806, 820, "2099-12-23T14:59:00.123Z")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.meta.path").value("/v1/requests"))
            .andReturn();
        long id = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.success.data.id").toString());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("created_by")).isEqualTo(890L);
        assertThat(row.get("cohort_id")).isEqualTo(806L);
        assertThat(row.get("title")).isEqualTo("2학기 성적증명서 제출");
        // 한국 12/23 23:59 = UTC 14:59, 초 아래는 버린다
        assertThat(((java.sql.Timestamp) row.get("closes_at")).toLocalDateTime()).isEqualTo(LocalDateTime.of(2099, 12, 23, 14, 59, 0));
        assertThat(row.get("published_at")).isNull();

        // 만든 요청을 상세 조회하면 보낸 값이 그대로 나온다 (받은 모양 = 보낸 모양)
        mockMvc.perform(asManager(get("/v1/requests/" + id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.title").value("2학기 성적증명서 제출"))
            .andExpect(jsonPath("$.success.data.closesAt").value("2099-12-23T14:59:00Z"));
    }

    @Test
    void createsProgramWideRequestWithoutCohort() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests")).content(body(null, 820, "2099-12-23T14:59:00Z")))
            .andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM requests WHERE title = '2학기 성적증명서 제출' AND cohort_id IS NULL")
            .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsInvalidFormatWithFieldNames() throws Exception {
        mockMvc.perform(asManager(post("/v1/requests")).content("""
                {"programId": 801, "cohortId": null, "templateId": 820, "title": "  ",
                 "academicYear": 1999, "semester": "3", "closesAt": null}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details", containsInAnyOrder("title", "academicYear", "semester", "closesAt")));
    }

    // 본문 형식은 맞지만 DB·현재 시각을 봐야 틀린 것을 아는 경우
    @ParameterizedTest
    @CsvSource({
        "999,  ,    820, 2099-12-23T14:59:00Z, programId",
        "801,  807, 820, 2099-12-23T14:59:00Z, cohortId",
        "801,  999, 820, 2099-12-23T14:59:00Z, cohortId",
        "801,  ,    821, 2099-12-23T14:59:00Z, templateId",
        "801,  ,    999, 2099-12-23T14:59:00Z, templateId",
        "801,  ,    820, 2020-01-01T00:00:00Z, closesAt"
    })
    void rejectsInvalidReferenceOrPastDeadline(long programId, Long cohortId, long templateId, String closesAt,
                                                String invalidField) throws Exception {
        mockMvc.perform(asManager(post("/v1/requests")).content(body(programId, cohortId, templateId, closesAt)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details", hasItem(invalidField)));
    }

    @Test
    void rejectsScholarAndMissingToken() throws Exception {
        mockMvc.perform(post("/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content(body(806, 820, "2099-12-23T14:59:00Z"))
                .with(jwt().jwt(token -> token.subject("890")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content(body(806, 820, "2099-12-23T14:59:00Z")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---------- 고치기 ----------

    @Test
    void updatesDraftRequest() throws Exception {
        mockMvc.perform(asManager(patch("/v1/requests/801")).content(body(806, 820, "2099-12-24T14:59:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.id").value(801))
            .andExpect(jsonPath("$.success.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.meta.path").value("/v1/requests/801"));

        Map<String, Object> row = row(801);
        assertThat(row.get("title")).isEqualTo("2학기 성적증명서 제출");
        assertThat(row.get("cohort_id")).isEqualTo(806L);
        assertThat(((java.sql.Timestamp) row.get("closes_at")).toLocalDateTime()).isEqualTo(LocalDateTime.of(2099, 12, 24, 14, 59, 0));
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("version")).isEqualTo(1);
    }

    @Test
    void rejectsUpdatingPublishedRequest() throws Exception {
        mockMvc.perform(asManager(patch("/v1/requests/802")).content(body(806, 820, "2099-12-24T14:59:00Z")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_EDITABLE"));

        assertThat(row(802).get("title")).isEqualTo("공개된 요청");
    }

    @Test
    void returnsNotFoundForMissingRequest() throws Exception {
        mockMvc.perform(asManager(patch("/v1/requests/999999")).content(body(806, 820, "2099-12-24T14:59:00Z")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidUpdateBody() throws Exception {
        mockMvc.perform(asManager(patch("/v1/requests/801")).content(body(807, 820, "2099-12-24T14:59:00Z")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details", hasItem("cohortId")));

        assertThat(row(801).get("title")).isEqualTo("고치기 전 제목");
    }

    // ---------- 도움 메서드 ----------

    private String body(Integer cohortId, long templateId, String closesAt) {
        return body(801, cohortId == null ? null : cohortId.longValue(), templateId, closesAt);
    }

    private String body(long programId, Long cohortId, long templateId, String closesAt) {
        return """
            {"programId": %d, "cohortId": %s, "templateId": %d, "title": "2학기 성적증명서 제출",
             "description": "학기 평점을 입력하세요", "academicYear": 2026, "semester": "2",
             "closesAt": "%s", "completionMessage": null}
            """.formatted(programId, cohortId, templateId, closesAt);
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT * FROM requests WHERE id = :id").param("id", id).query().singleRow();
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON)
            .with(jwt().jwt(token -> token.subject("890")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
