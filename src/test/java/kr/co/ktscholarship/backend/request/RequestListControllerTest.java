package kr.co.ktscholarship.backend.request;

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
class RequestListControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    /*
     * 요청 4건
     * 701 진행 중 · 프로그램 701 · 전체 기수 · 11/01 생성 · 대상 4명(확인, 보완 필요, 작성 중, 제출 없음)
     * 702 작성 중 · 프로그램 701 · 6기     · 11/05 생성 · 대상 없음
     * 703 마감    · 프로그램 701 · 전체 기수 · 10/01 생성 · 대상 1명(제출)
     * 704 진행 중 · 프로그램 702 · 전체 기수 · 11/03 생성 · 대상 1명(재제출)
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (701, '프로그램 A'), (702, '프로그램 B')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (706, 701, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (790, '담당자', 'request-manager@example.test', 'test-only', 'MANAGER'),
                (791, '장학생1', 'request-s1@example.test', 'test-only', 'SCHOLAR'),
                (792, '장학생2', 'request-s2@example.test', 'test-only', 'SCHOLAR'),
                (793, '장학생3', 'request-s3@example.test', 'test-only', 'SCHOLAR'),
                (794, '장학생4', 'request-s4@example.test', 'test-only', 'SCHOLAR'),
                (795, '장학생5', 'request-s5@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name, phone, major)
            SELECT id, 706, '학교', '010-0000-0000', '전공' FROM users WHERE id BETWEEN 791 AND 795
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (720, 'TEST_GRADE', '성적증명서', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at, created_at) VALUES
                (701, 701, NULL, 720, '성적증명서 제출', 2026, '1',
                    '2026-11-14 14:59:59', 'IN_PROGRESS', 790, '2026-11-01 00:00:00', '2026-11-01 00:00:00'),
                (702, 701, 706, 720, '작성 중 요청', 2026, '1',
                    '2026-11-30 14:59:59', 'DRAFT', 790, NULL, '2026-11-05 00:00:00'),
                (703, 701, NULL, 720, '마감된 요청', 2026, '1',
                    '2026-10-10 14:59:59', 'CLOSED', 790, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
                (704, 702, NULL, 720, '다른 프로그램 요청', 2026, '1',
                    '2026-11-20 14:59:59', 'IN_PROGRESS', 790, '2026-11-03 00:00:00', '2026-11-03 00:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (request_id, scholar_id) VALUES
                (701, 791), (701, 792), (701, 793), (701, 794),
                (703, 791),
                (704, 795)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (request_id, scholar_id, status, answers) VALUES
                (701, 791, 'CONFIRMED', '{}' FORMAT JSON),
                (701, 792, 'REVISION_REQUIRED', '{}' FORMAT JSON),
                (701, 793, 'DRAFT', '{}' FORMAT JSON),
                (703, 791, 'SUBMITTED', '{}' FORMAT JSON),
                (704, 795, 'RESUBMITTED', '{}' FORMAT JSON)
            """).update();
    }

    @Test
    void returnsRequestsWithSubmissionCounts() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.page").value(1))
            .andExpect(jsonPath("$.success.data.pageSize").value(20))
            .andExpect(jsonPath("$.success.data.total").value(4))
            // 진행 중(최신순) → 작성 중 → 마감
            .andExpect(jsonPath("$.success.data.items[*].id", contains(704, 701, 702, 703)))
            .andExpect(jsonPath("$.success.data.items[1].title").value("성적증명서 제출"))
            .andExpect(jsonPath("$.success.data.items[1].templateCode").value("TEST_GRADE"))
            .andExpect(jsonPath("$.success.data.items[1].cohortName").doesNotExist())
            .andExpect(jsonPath("$.success.data.items[1].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.success.data.items[1].publishedAt").value("2026-11-01T00:00:00Z"))
            .andExpect(jsonPath("$.success.data.items[1].closesAt").value("2026-11-14T14:59:59Z"))
            // 대상 4명 중 확인 1 + 보완 필요 1 = 제출 2, 작성 중·제출 없음은 미제출
            .andExpect(jsonPath("$.success.data.items[1].targetCount").value(4))
            .andExpect(jsonPath("$.success.data.items[1].submittedCount").value(2))
            .andExpect(jsonPath("$.success.data.items[1].revisionCount").value(1))
            // 작성 중: 대상 없음, 공개 전
            .andExpect(jsonPath("$.success.data.items[2].cohortName").value("6기"))
            .andExpect(jsonPath("$.success.data.items[2].publishedAt").doesNotExist())
            .andExpect(jsonPath("$.success.data.items[2].targetCount").value(0))
            .andExpect(jsonPath("$.success.data.items[2].submittedCount").value(0))
            .andExpect(jsonPath("$.success.data.items[2].revisionCount").value(0))
            // 제출·재제출도 제출 수에 포함
            .andExpect(jsonPath("$.success.data.items[0].submittedCount").value(1))
            .andExpect(jsonPath("$.success.data.items[3].submittedCount").value(1));
    }

    @Test
    void filtersByStatusAndCountsTotalWithSameCondition() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests").param("status", "IN_PROGRESS")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(2))
            .andExpect(jsonPath("$.success.data.items[*].id", contains(704, 701)));

        mockMvc.perform(asManager(get("/v1/requests").param("status", "CLOSED")))
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[*].id", contains(703)));
    }

    @Test
    void filtersByProgramAndCohort() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests").param("programId", "701")))
            .andExpect(jsonPath("$.success.data.total").value(3))
            .andExpect(jsonPath("$.success.data.items[*].id", contains(701, 702, 703)));

        mockMvc.perform(asManager(get("/v1/requests").param("cohortId", "706")))
            .andExpect(jsonPath("$.success.data.total").value(1))
            .andExpect(jsonPath("$.success.data.items[*].id", contains(702)));

        // 없는 프로그램은 오류가 아니라 빈 목록
        mockMvc.perform(asManager(get("/v1/requests").param("programId", "999")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.total").value(0))
            .andExpect(jsonPath("$.success.data.items", hasSize(0)));
    }

    @Test
    void returnsRequestedPageWithTotalOfAllPages() throws Exception {
        mockMvc.perform(asManager(get("/v1/requests").param("page", "2").param("page_size", "1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.page").value(2))
            .andExpect(jsonPath("$.success.data.pageSize").value(1))
            .andExpect(jsonPath("$.success.data.total").value(4))
            .andExpect(jsonPath("$.success.data.items[*].id", contains(701)));
    }

    @ParameterizedTest
    @CsvSource({
        "status, ABC",
        "status, FINISHED",
        "page, 0",
        "page_size, 0",
        "page_size, 101",
        "programId, 0"
    })
    void rejectsInvalidParameter(String name, String value) throws Exception {
        mockMvc.perform(asManager(get("/v1/requests").param(name, value)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsScholar() throws Exception {
        mockMvc.perform(get("/v1/requests").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/v1/requests"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // 다른 목록 응답의 Item과 Swagger 스키마 이름이 겹치면 한쪽 문서가 덮어써진다
    // 요청 목록은 자기 이름(RequestListItem)의 스키마를 가리키는지만 본다 (다른 목록의 Item 이름에 기대지 않음)
    @Test
    void keepsSwaggerSchemaSeparateFromOtherItems() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.RequestListItem.properties.submittedCount").exists())
            .andExpect(jsonPath("$.components.schemas.RequestListResponse.properties.items.items['$ref']")
                .value("#/components/schemas/RequestListItem"));
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
