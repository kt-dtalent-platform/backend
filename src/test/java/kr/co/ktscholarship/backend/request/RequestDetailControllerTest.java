package kr.co.ktscholarship.backend.request;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class RequestDetailControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    // 801: 작성 중 · 전체 기수 · 안내문 없음 / 802: 진행 중 · 6기 · 안내문 있음
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (801, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (806, 801, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role)
            VALUES (890, '담당자', 'detail-manager@example.test', 'test-only', 'MANAGER')
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (820, 'TEST_DETAIL', '성적증명서', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, description, academic_year, semester,
                closes_at, status, completion_message, created_by, published_at) VALUES
                (801, 801, NULL, 820, '2026학년도 2학기 성적증명서 제출', NULL, 2026, '2',
                    '2026-12-23 14:59:00', 'DRAFT', NULL, 890, NULL),
                (802, 801, 806, 820, '여름학기 성적 제출', '학기 평점을 입력하세요', 2026, 'SUMMER',
                    '2026-11-20 14:59:59', 'IN_PROGRESS', '제출해 주셔서 감사합니다', 890, '2026-11-10 01:00:00')
            """).update();
    }

    @Test
    void returnsDraftRequestForEditForm() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/801")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.id").value(801))
            .andExpect(jsonPath("$.success.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.success.data.programId").value(801))
            .andExpect(jsonPath("$.success.data.cohortId").doesNotExist())
            .andExpect(jsonPath("$.success.data.templateId").value(820))
            .andExpect(jsonPath("$.success.data.title").value("2026학년도 2학기 성적증명서 제출"))
            .andExpect(jsonPath("$.success.data.description").doesNotExist())
            .andExpect(jsonPath("$.success.data.academicYear").value(2026))
            .andExpect(jsonPath("$.success.data.semester").value("2"))
            // 한국 시간 12/23 23:59 = UTC 14:59
            .andExpect(jsonPath("$.success.data.closesAt").value("2026-12-23T14:59:00Z"))
            .andExpect(jsonPath("$.success.data.completionMessage").doesNotExist())
            .andExpect(jsonPath("$.success.data.publishedAt").doesNotExist())
            .andExpect(jsonPath("$.meta.path").value("/v1/requests/801"));
    }

    // 공개된 요청도 조회된다. 프론트가 status를 보고 "이미 공개한 요청" 안내를 띄운다
    @Test
    void returnsPublishedRequestToo() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/802")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.success.data.cohortId").value(806))
            .andExpect(jsonPath("$.success.data.description").value("학기 평점을 입력하세요"))
            .andExpect(jsonPath("$.success.data.semester").value("SUMMER"))
            .andExpect(jsonPath("$.success.data.completionMessage").value("제출해 주셔서 감사합니다"))
            .andExpect(jsonPath("$.success.data.publishedAt").value("2026-11-10T01:00:00Z"));
    }

    @Test
    void returnsNotFoundForMissingRequest() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/999999")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidRequestId(String requestId) throws Exception {
        mockMvc.perform(asManager(get("/v1/requests/" + requestId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsScholar() throws Exception {
        mockMvc.perform(get("/v1/requests/801").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/v1/requests/801"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
