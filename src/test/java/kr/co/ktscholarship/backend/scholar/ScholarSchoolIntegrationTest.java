package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ScholarSchoolIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id, name) VALUES (8401, '학교 연결 검증')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (8406, 8401, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (8411, '검증 학생', 'school-link@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_id, school_name)
            VALUES (8411, 8406, 3, '등록 당시 학교명')
            """).update();
    }

    @Test
    void preservesSeedCodesGroupsAndNullCodes() {
        assertThat(jdbc.sql("SELECT school_code FROM schools WHERE id = 1").query(String.class).single())
            .isEqualTo("0002748");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools WHERE school_code IS NULL").query(Long.class).single())
            .isEqualTo(4L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools WHERE school_group = '대학'").query(Long.class).single())
            .isEqualTo(251L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools WHERE school_group = '전문대학'").query(Long.class).single())
            .isEqualTo(160L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools WHERE school_group = '대학원대학'").query(Long.class).single())
            .isEqualTo(44L);
    }

    @Test
    void registrationResolvesSelectedIdToCanonicalSchoolName() throws Exception {
        mvc.perform(manager(post("/v1/scholars")).content(registration("\"schoolId\":3,")))
            .andExpect(status().isCreated());
        assertThat(jdbc.sql("""
            SELECT s.school_id, s.school_name FROM scholar_profiles s JOIN users u ON u.id = s.user_id
            WHERE u.email = 'school-register@example.test'
            """).query().singleRow()).containsEntry("school_id", 3L).containsEntry("school_name", "가천대학교");
    }

    @Test
    void registrationWithoutIdPreservesFreeText() throws Exception {
        mvc.perform(manager(post("/v1/scholars")).content(registration("")))
            .andExpect(status().isCreated());
        assertThat(jdbc.sql("""
            SELECT s.school_id, s.school_name FROM scholar_profiles s JOIN users u ON u.id = s.user_id
            WHERE u.email = 'school-register@example.test'
            """).query().singleRow()).containsEntry("school_id", null).containsEntry("school_name", "직접 입력 학교");
    }

    @Test
    void unknownRegistrationIdIs400AndCreatesNoAccount() throws Exception {
        mvc.perform(manager(post("/v1/scholars")).content(registration("\"schoolId\":999999,")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0]").value("schoolId"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'school-register@example.test'")
            .query(Long.class).single()).isZero();
    }

    @Test
    void updatesSelectionAndReturnsIdInDetailAndList() throws Exception {
        mvc.perform(manager(patch("/v1/scholars/8411"))
                .content("{\"schoolId\":6,\"university\":\"다른 표기\"}"))
            .andExpect(status().isOk());
        mvc.perform(manager(get("/v1/scholars/8411")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.schoolId").value(6))
            .andExpect(jsonPath("$.success.data.university").value("가톨릭대학교"));
        mvc.perform(manager(get("/v1/scholars").param("keyword", "school-link@example.test")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items[0].schoolId").value(6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"schoolId\":null,\"university\":\"목록 밖 학교\"}",
        "{\"university\":\"목록 밖 학교\"}"})
    void freeTextClearsPreviousSchoolId(String body) throws Exception {
        mvc.perform(manager(patch("/v1/scholars/8411")).content(body)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT school_id, school_name FROM scholar_profiles WHERE user_id = 8411")
            .query().singleRow()).containsEntry("school_id", null).containsEntry("school_name", "목록 밖 학교");
    }

    @Test
    void unrelatedPatchAndSchoolRenamePreserveIdAndStoredName() throws Exception {
        jdbc.sql("UPDATE schools SET name = '학교 목록의 새 이름' WHERE id = 3").update();
        mvc.perform(manager(patch("/v1/scholars/8411")).content("{\"major\":\"전공 수정\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT school_id, school_name FROM scholar_profiles WHERE user_id = 8411")
            .query().singleRow()).containsEntry("school_id", 3L).containsEntry("school_name", "등록 당시 학교명");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"schoolId\":999999,\"university\":\"학교\",\"name\":\"바꾸면 안 됨\"}",
        "{\"schoolId\":0,\"university\":\"학교\"}", "{\"schoolId\":-1,\"university\":\"학교\"}",
        "{\"schoolId\":\"3\",\"university\":\"학교\"}", "{\"schoolId\":3.5,\"university\":\"학교\"}",
        "{\"schoolId\":null}", "{\"schoolId\":3}", "{\"schoolId\":3,\"university\":null}"})
    void invalidSchoolPatchIs400AndPreservesProfile(String body) throws Exception {
        mvc.perform(manager(patch("/v1/scholars/8411")).content(body)).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 8411").query(String.class).single())
            .isEqualTo("검증 학생");
        assertThat(jdbc.sql("SELECT school_id FROM scholar_profiles WHERE user_id = 8411").query(Long.class).single())
            .isEqualTo(3L);
    }

    @Test
    void scholarCannotChangeSchoolThroughManagerEndpoint() throws Exception {
        mvc.perform(patch("/v1/scholars/8411").contentType(MediaType.APPLICATION_JSON)
                .content("{\"schoolId\":6,\"university\":\"학교\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
    }

    private String registration(String schoolId) {
        return "{" + schoolId + "\"cohortId\":8406,\"name\":\"새 학생\","
            + "\"email\":\"school-register@example.test\",\"university\":\"직접 입력 학교\"}";
    }

    private MockHttpServletRequestBuilder manager(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
