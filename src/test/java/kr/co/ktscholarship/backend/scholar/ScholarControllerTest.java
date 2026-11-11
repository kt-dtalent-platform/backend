package kr.co.ktscholarship.backend.scholar;

import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScholarControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id, name) VALUES (101, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (106, 101, '6기'), (107, 101, '7기')")
            .update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role, status, deleted_at) VALUES
                (201, '홍길동', 'hong@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (202, '김정윤', 'student%202@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (203, '이승완', 'lee@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', NULL),
                (204, '졸업생', 'graduate@example.test', 'test-only', 'SCHOLAR', 'INACTIVE', NULL),
                (205, '삭제 계정', 'deleted@example.test', 'test-only', 'SCHOLAR', 'ACTIVE', '2026-11-01'),
                (206, '담당자', 'manager@example.test', 'test-only', 'MANAGER', 'ACTIVE', NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name, phone, status) VALUES
                (201, 106, '테스트대학교', '010-1234-5678', 'ENROLLED'),
                (202, 106, '테스트대학교', NULL, 'ENROLLED'),
                (203, 107, '테스트대학교', '01087654321', 'LEAVE'),
                (204, 107, '테스트대학교', '123', 'GRADUATED'),
                (205, 106, '테스트대학교', '010-0000-9999', 'ENROLLED')
            """).update();
    }

    @Test
    void returnsDefaultPageWithMaskedPhonesAndCommonResponse() throws Exception {
        mockMvc.perform(get("/v1/scholars").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.page").value(1))
            .andExpect(jsonPath("$.success.data.page_size").value(20))
            .andExpect(jsonPath("$.success.data.total").value(4))
            .andExpect(jsonPath("$.success.data.items.length()").value(4))
            .andExpect(jsonPath("$.success.data.items[0].id").value(201))
            .andExpect(jsonPath("$.success.data.items[0].name").value("홍길동"))
            .andExpect(jsonPath("$.success.data.items[0].email").value("hong@example.test"))
            .andExpect(jsonPath("$.success.data.items[0].maskedPhone").value("010-****-5678"))
            .andExpect(jsonPath("$.success.data.items[0].cohortName").value("6기"))
            .andExpect(jsonPath("$.success.data.items[0].university").value("테스트대학교"))
            .andExpect(jsonPath("$.success.data.items[0].status").value("ENROLLED"))
            .andExpect(jsonPath("$.success.data.items[0].accountStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.success.data.items[0].phone").doesNotExist())
            .andExpect(jsonPath("$.success.data.items[1].maskedPhone").value(nullValue()))
            .andExpect(jsonPath("$.success.data.items[2].maskedPhone").value("010-****-4321"))
            .andExpect(jsonPath("$.success.data.items[3].maskedPhone").value("****"))
            .andExpect(jsonPath("$.success.data.items[3].accountStatus").value("INACTIVE"))
            .andExpect(jsonPath("$.error").value(nullValue()))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"홍길동", "hong@example.test"})
    void searchesNameOrEmail(String keyword) throws Exception {
        mockMvc.perform(get("/v1/scholars").param("keyword", keyword).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(201));
    }

    @Test
    void filtersCohortAndCountsBeforePagination() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("cohortId", "107").param("page_size", "1").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(2))
            .andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203));
    }

    @Test
    void combinesCohortAndScholarStatusFilters() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("cohortId", "106").param("status", "LEAVE").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(0))
            .andExpect(jsonPath("$.success.data.items").isEmpty());
        mockMvc.perform(get("/v1/scholars").param("status", "LEAVE").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203));
    }

    @Test
    void appliesOneBasedPageWithoutChangingTotal() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("page", "2").param("page_size", "2").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.page").value(2))
            .andExpect(jsonPath("$.success.data.page_size").value(2))
            .andExpect(jsonPath("$.success.data.total").value(4))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203))
            .andExpect(jsonPath("$.success.data.items[1].id").value(204));
    }

    @Test
    void treatsSearchWildcardsAsLiteralCharacters() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("keyword", "%").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(202));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "page=0", "page=-1", "page_size=0", "page_size=101", "page=abc", "cohortId=0", "status=UNKNOWN",
        "submitStatus=NOT_SUBMITTED", "requestId=1", "requestId=1&submitStatus=UNKNOWN",
        "requestId=0&submitStatus=DRAFT"
    })
    void rejectsInvalidFilters(String query) throws Exception {
        mockMvc.perform(get("/v1/scholars?" + query).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnauthenticatedRequestWithCommon401() throws Exception {
        mockMvc.perform(get("/v1/scholars"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars"));
    }

    @Test
    void rejectsScholarRequestWithCommon403() throws Exception {
        mockMvc.perform(get("/v1/scholars")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"NOT_SUBMITTED", "DRAFT", "SUBMITTED", "RESUBMITTED", "REVISION_REQUIRED", "CONFIRMED"})
    void filtersAllSixSubmissionStatesWithinRequestTargets(String submitStatus) throws Exception {
        addRequestTargets();
        if (!submitStatus.equals("NOT_SUBMITTED")) {
            jdbc.sql("""
                INSERT INTO submissions (id, request_id, scholar_id, status, answers)
                VALUES (401, 301, 201, :status, '{}' FORMAT JSON),
                    (402, 301, 202, :status, '{}' FORMAT JSON),
                    (403, 302, 203, :status, '{}' FORMAT JSON)
                """).param("status", submitStatus).update();
        }
        mockMvc.perform(get("/v1/scholars").param("requestId", "301").param("submitStatus", submitStatus)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(201));
    }

    @Test
    void submissionFilterCombinesWithSearchCohortAndPagination() throws Exception {
        addRequestTargets();
        jdbc.sql("INSERT INTO request_targets (id, request_id, scholar_id) VALUES (505, 301, 202)").update();
        mockMvc.perform(get("/v1/scholars").param("requestId", "301").param("submitStatus", "NOT_SUBMITTED")
                .param("cohortId", "106").param("status", "ENROLLED").param("page_size", "1").param("page", "2")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(2))
            .andExpect(jsonPath("$.success.data.items[0].id").value(202));
        mockMvc.perform(get("/v1/scholars").param("requestId", "301").param("submitStatus", "NOT_SUBMITTED")
                .param("keyword", "홍길동").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(201));
    }

    @Test
    void missingRequestOrUnmatchedSubmissionStateReturnsEmptyList() throws Exception {
        addRequestTargets();
        for (String requestId : new String[]{"301", "999"}) {
            mockMvc.perform(get("/v1/scholars").param("requestId", requestId).param("submitStatus", "CONFIRMED")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.total").value(0))
                .andExpect(jsonPath("$.success.data.items").isEmpty());
        }
    }

    private void addRequestTargets() {
        jdbc.sql("INSERT INTO request_templates (id, code, name, default_fields) VALUES (101, 'FILTER_TEST', '검증', '[]' FORMAT JSON)").update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, template_id, title, academic_year, semester, closes_at, status, created_by, published_at)
            VALUES (301, 101, 101, '필터 대상', 2026, '2', '2026-12-01', 'IN_PROGRESS', 206, '2026-11-01'),
                (302, 101, 101, '다른 요청', 2026, '2', '2026-12-01', 'IN_PROGRESS', 206, '2026-11-01')
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (id, request_id, scholar_id) VALUES
                (501, 301, 201), (503, 301, 205), (504, 302, 203)
            """).update();
    }

}
