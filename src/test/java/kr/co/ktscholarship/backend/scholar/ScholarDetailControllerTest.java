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
import org.junit.jupiter.params.provider.CsvSource;
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
class ScholarDetailControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id, name) VALUES (101, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (106, 101, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role, deleted_at) VALUES
                (201, '홍길동', 'hong@example.test', 'test-only', 'SCHOLAR', NULL),
                (202, '정보 없음', 'nullable@example.test', 'test-only', 'SCHOLAR', NULL),
                (203, '삭제 계정', 'deleted@example.test', 'test-only', 'SCHOLAR', '2026-11-01'),
                (204, '담당자', 'manager@example.test', 'test-only', 'MANAGER', NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name, phone, major) VALUES
                (201, 106, '테스트대학교', '010-1234-5678', '소프트웨어학부'),
                (202, 106, '테스트대학교', NULL, NULL),
                (203, 106, '테스트대학교', '010-0000-9999', NULL),
                (204, 106, '테스트대학교', '010-0000-8888', NULL)
            """).update();
    }

    @Test
    void returnsBasicDetailsWithMaskedPhoneAndCommonResponse() throws Exception {
        mockMvc.perform(get("/v1/scholars/201").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.id").value(201))
            .andExpect(jsonPath("$.success.data.name").value("홍길동"))
            .andExpect(jsonPath("$.success.data.email").value("hong@example.test"))
            .andExpect(jsonPath("$.success.data.maskedPhone").value("010-****-5678"))
            .andExpect(jsonPath("$.success.data.university").value("테스트대학교"))
            .andExpect(jsonPath("$.success.data.major").value("소프트웨어학부"))
            .andExpect(jsonPath("$.success.data.cohort.id").value(106))
            .andExpect(jsonPath("$.success.data.cohort.name").value("6기"))
            .andExpect(jsonPath("$.success.data.status").value("ENROLLED"))
            .andExpect(jsonPath("$.success.data.phone").doesNotExist())
            .andExpect(jsonPath("$.error").value(nullValue()))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars/201"));
    }

    @Test
    void keepsNullablePhoneAndMajor() throws Exception {
        mockMvc.perform(get("/v1/scholars/202").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.maskedPhone").value(nullValue()))
            .andExpect(jsonPath("$.success.data.major").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({"01012345678, 010-****-5678", "02-1234-5678, 02-****-5678", "123, ****"})
    void masksPhoneWithoutExposingTheOriginal(String phone, String expected) throws Exception {
        jdbc.sql("UPDATE scholar_profiles SET phone = :phone WHERE user_id = 201")
            .param("phone", phone).update();

        mockMvc.perform(get("/v1/scholars/201").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.maskedPhone").value(expected));
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 203, 204})
    void rejectsMissingDeletedOrNonScholarAccounts(long id) throws Exception {
        mockMvc.perform(get("/v1/scholars/{scholarId}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.success").value(nullValue()))
            .andExpect(jsonPath("$.error.code").value("SCHOLAR_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("장학생을 찾을 수 없습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidIds(String id) throws Exception {
        mockMvc.perform(get("/v1/scholars/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnauthenticatedRequestWithCommon401() throws Exception {
        mockMvc.perform(get("/v1/scholars/201"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars/201"));
    }

    @Test
    void rejectsScholarRequestWithCommon403() throws Exception {
        mockMvc.perform(get("/v1/scholars/201")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars/201"));
    }
    @Test
    void returnsZeroSummaryWithoutAssignedRequests() throws Exception {
        mockMvc.perform(get("/v1/scholars/201").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.submissionSummary.total").value(0))
            .andExpect(jsonPath("$.success.data.submissionSummary.confirmed").value(0))
            .andExpect(jsonPath("$.success.data.submissionSummary.pending").value(0));
    }

    @Test
    void countsPublishedAssignmentsIncludingUnsubmittedAndAllPendingStates() throws Exception {
        jdbc.sql("INSERT INTO request_templates (id, code, name, default_fields) VALUES (101, 'SUMMARY_TEST', '검증', '[]' FORMAT JSON)").update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, template_id, title, academic_year, semester, closes_at, status, created_by, published_at)
            VALUES (301, 101, 101, '미제출', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (302, 101, 101, '작성 중', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (303, 101, 101, '제출', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (304, 101, 101, '재제출', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (305, 101, 101, '보완', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (306, 101, 101, '확인 완료', 2026, '2', '2026-12-01', 'CLOSED', 204, '2026-11-01'),
                (307, 101, 101, '공개 전', 2026, '2', '2026-12-01', 'DRAFT', 204, NULL),
                (308, 101, 101, '공개 기록 없음', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, NULL),
                (309, 101, 101, '다른 장학생', 2026, '2', '2026-12-01', 'IN_PROGRESS', 204, '2026-11-01'),
                (310, 101, 101, '마감 미제출', 2026, '2', '2026-12-01', 'CLOSED', 204, '2026-11-01')
            """).update();
        jdbc.sql("""
            INSERT INTO request_targets (id, request_id, scholar_id) VALUES
                (501,301,201), (502,302,201), (503,303,201), (504,304,201), (505,305,201),
                (506,306,201), (507,307,201), (508,308,201), (509,309,202), (510,310,201)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers) VALUES
                (402,302,201,'DRAFT','{}' FORMAT JSON), (403,303,201,'SUBMITTED','{}' FORMAT JSON),
                (404,304,201,'RESUBMITTED','{}' FORMAT JSON), (405,305,201,'REVISION_REQUIRED','{}' FORMAT JSON),
                (406,306,201,'CONFIRMED','{}' FORMAT JSON), (407,307,201,'CONFIRMED','{}' FORMAT JSON),
                (408,308,201,'CONFIRMED','{}' FORMAT JSON), (409,309,202,'CONFIRMED','{}' FORMAT JSON),
                (410,301,202,'CONFIRMED','{}' FORMAT JSON)
            """).update();
        mockMvc.perform(get("/v1/scholars/201").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.submissionSummary.total").value(7))
            .andExpect(jsonPath("$.success.data.submissionSummary.confirmed").value(1))
            .andExpect(jsonPath("$.success.data.submissionSummary.pending").value(6));
    }

}
