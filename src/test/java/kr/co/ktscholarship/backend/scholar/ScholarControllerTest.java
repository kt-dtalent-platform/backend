package kr.co.ktscholarship.backend.scholar;

import static org.hamcrest.Matchers.nullValue;
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
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
        mockMvc.perform(get("/v1/scholars"))
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
        mockMvc.perform(get("/v1/scholars").param("keyword", keyword))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(201));
    }

    @Test
    void filtersCohortAndCountsBeforePagination() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("cohortId", "107").param("page_size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(2))
            .andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203));
    }

    @Test
    void combinesCohortAndScholarStatusFilters() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("cohortId", "106").param("status", "LEAVE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(0))
            .andExpect(jsonPath("$.success.data.items").isEmpty());
        mockMvc.perform(get("/v1/scholars").param("status", "LEAVE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203));
    }

    @Test
    void appliesOneBasedPageWithoutChangingTotal() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("page", "2").param("page_size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.page").value(2))
            .andExpect(jsonPath("$.success.data.page_size").value(2))
            .andExpect(jsonPath("$.success.data.total").value(4))
            .andExpect(jsonPath("$.success.data.items[0].id").value(203))
            .andExpect(jsonPath("$.success.data.items[1].id").value(204));
    }

    @Test
    void treatsSearchWildcardsAsLiteralCharacters() throws Exception {
        mockMvc.perform(get("/v1/scholars").param("keyword", "%"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[0].id").value(202));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "page=0", "page=-1", "page_size=0", "page_size=101", "page=abc", "cohortId=0", "status=UNKNOWN",
        "submitStatus=NOT_SUBMITTED", "requestId=1"
    })
    void rejectsInvalidOrUnimplementedFilters(String query) throws Exception {
        mockMvc.perform(get("/v1/scholars?" + query))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
