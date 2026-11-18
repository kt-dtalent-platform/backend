package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class ScholarImportControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper mapper;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (8601,'일괄 등록 검증')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (8606,8601,'검증 기수')").update();
        jdbc.sql("""
            INSERT INTO users (id,name,email,password_hash,role) VALUES
                (8681,'기존 학생','import-existing@example.test','test-only','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (8681,8606,'기존 학교')").update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM scholar_profiles WHERE cohort_id=8606").update();
        jdbc.sql("DELETE FROM users WHERE email LIKE 'import-%@example.test'").update();
        jdbc.sql("DELETE FROM cohorts WHERE id=8606").update();
        jdbc.sql("DELETE FROM programs WHERE id=8601").update();
    }

    @Test
    void responseToStringDoesNotExposeTemporaryPassword() {
        var response=new ScholarImportResponse(1,List.of(new ScholarImportResponse.Item(2,1,"email@example.test","secret")));
        assertThat(response.toString()).doesNotContain("secret");
    }

    @Test
    void bulkJsonMatchesFrontendAndReturnsCreatedWithUsablePasswords() throws Exception {
        var response = mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("import-second@example.test"))))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success.data.created").value(2)).andReturn().getResponse();
        var items = mapper.readTree(response.getContentAsString()).path("success").path("data").path("items");
        for (var item : items) {
            var user = jdbc.sql("SELECT password_hash,password_change_required FROM users WHERE id=:id")
                .param("id", item.path("id").asLong()).query().singleRow();
            assertThat(encoder.matches(item.path("temporaryPassword").asText(), (String) user.get("password_hash"))).isTrue();
            assertThat(user.get("password_change_required")).isEqualTo(true);
        }
        assertThat(profile("import-first@example.test")).containsEntry("school_name", "직접 입력 학교")
            .containsEntry("school_id", null).containsEntry("student_no", "001234");
    }

    @Test
    void bulkJsonDuplicateEmailIsAStringListAndRollsBackPreviousRows() throws Exception {
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("import-existing@example.test"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"))
            .andExpect(jsonPath("$.error.details[0]").value("import-existing@example.test"));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonReportsAllRequestAndDatabaseDuplicatesBeforeSaving() throws Exception {
        jdbc.sql("INSERT INTO users (id,name,email,password_hash,role) VALUES (8682,'기존','import-other@example.test','test-only','SCHOLAR')").update();
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"),
                bulkRow("IMPORT-FIRST@example.test"), bulkRow("import-second@example.test"),
                bulkRow("IMPORT-SECOND@example.test"), bulkRow("import-existing@example.test"),
                bulkRow("IMPORT-OTHER@example.test"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details.length()").value(4))
            .andExpect(jsonPath("$.error.details[0]").value("IMPORT-FIRST@example.test"))
            .andExpect(jsonPath("$.error.details[1]").value("IMPORT-SECOND@example.test"))
            .andExpect(jsonPath("$.error.details[2]").value("import-existing@example.test"))
            .andExpect(jsonPath("$.error.details[3]").value("IMPORT-OTHER@example.test"));
        assertThat(newAccounts()).isEqualTo(1);
    }

    @Test
    void bulkJsonReusesDeletedEmailAndRollsBackForUnknownSchool() throws Exception {
        jdbc.sql("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=8681").update();
        mvc.perform(bulk(List.of(bulkRow("import-existing@example.test"))))
            .andExpect(status().isCreated());
        var invalid = new java.util.HashMap<>(bulkRow("import-second@example.test"));
        invalid.put("schoolId",999999);
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"),invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].row").value(3))
            .andExpect(jsonPath("$.error.details[0].field").value("schoolId"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE email='import-first@example.test'").query(Long.class).single()).isZero();
    }

    @Test
    void bulkJsonRejectsCaseInsensitiveDuplicatesAndInvalidRowsWithoutSaving() throws Exception {
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("IMPORT-FIRST@example.test"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0]").value("IMPORT-FIRST@example.test"));
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("invalid"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].row").value(3));
        assertThat(newAccounts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"cohortId\":8606,\"scholars\":[]}",
        "{\"cohortId\":8606,\"scholars\":[null]}",
        "{\"cohortId\":0,\"scholars\":[{}]}"
    })
    void bulkJsonRejectsInvalidEnvelope(String body) throws Exception {
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest());
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonReturnsRowErrorsForNullScholars() throws Exception {
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json")
                .content("{\"cohortId\":8606,\"scholars\":[null,null]}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details.length()").value(2))
            .andExpect(jsonPath("$.error.details[*].row", org.hamcrest.Matchers.containsInAnyOrder(2,3)))
            .andExpect(jsonPath("$.error.details[0].field").value("scholars"))
            .andExpect(jsonPath("$.error.details[0].message").isNotEmpty());
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonAllows500RowsButRejects501BeforeRegistration() throws Exception {
        var rows = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (int i = 0; i < 500; i++) rows.add(bulkRow("invalid"));
        mvc.perform(bulk(rows)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[499].row").value(501));
        rows.add(bulkRow("import-first@example.test"));
        mvc.perform(bulk(rows)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0]").value("scholars"));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonRequiresManagerAndSupportsSelectedSchoolAndMissingCohort() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("cohortId",8606,"scholars",List.of(bulkRow("import-first@example.test"))));
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body.replace("8606", "999999"))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isNotFound());
        var row = new java.util.HashMap<>(bulkRow("import-first@example.test"));
        row.put("schoolId", 3);
        mvc.perform(bulk(List.of(row))).andExpect(status().isCreated());
        assertThat(profile("import-first@example.test")).containsEntry("school_id",3L).containsEntry("school_name","가천대학교");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bulk(List<java.util.Map<String,Object>> rows) throws Exception {
        return post("/v1/scholars/bulk").contentType("application/json")
            .content(mapper.writeValueAsString(java.util.Map.of("cohortId",8606,"scholars",rows)))
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    private java.util.Map<String,Object> bulkRow(String email) {
        var row = new java.util.HashMap<String,Object>();
        row.put("name","일괄 학생"); row.put("email",email); row.put("university","직접 입력 학교");
        row.put("phone",null); row.put("major",null); row.put("studentNo","001234");
        return row;
    }

    private long newAccounts() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE email LIKE 'import-%@example.test' AND id<>8681").query(Long.class).single();
    }

    private java.util.Map<String,Object> profile(String email) {
        return jdbc.sql("SELECT p.* FROM scholar_profiles p JOIN users u ON u.id=p.user_id WHERE u.email=:email AND u.deleted_at IS NULL")
            .param("email",email).query().singleRow();
    }
}
