package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class ScholarUpdateControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ScholarUpdateRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.sql("INSERT INTO programs (id, name) VALUES (101, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (106, 101, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role, deleted_at) VALUES
                (201, '기존 이름', 'scholar@example.test', 'test-only', 'SCHOLAR', NULL),
                (202, '다른 장학생', 'other@example.test', 'test-only', 'SCHOLAR', NULL),
                (203, '삭제 계정', 'deleted@example.test', 'test-only', 'SCHOLAR', '2026-11-01'),
                (204, '담당자', 'manager@example.test', 'test-only', 'MANAGER', NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name, phone, major, updated_at)
            SELECT id, 106, '기존 학교', '010-1234-5678', '기존 전공', '2020-01-01' FROM users
            WHERE id BETWEEN 201 AND 204
            """).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM scholar_profiles WHERE user_id BETWEEN 201 AND 204").update();
        jdbc.sql("DELETE FROM users WHERE id BETWEEN 201 AND 204").update();
        jdbc.sql("DELETE FROM cohorts WHERE id = 106").update();
        jdbc.sql("DELETE FROM programs WHERE id = 101").update();
    }

    @Test
    void updatesBothTablesAndReturnsPersistedTimestamp() throws Exception {
        LocalDateTime before = LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
        mockMvc.perform(patch("/v1/scholars/201").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"수정 이름","phone":"010-9876-5432","university":"수정 학교","major":"수정 전공"}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.id").value(201))
            .andExpect(jsonPath("$.success.data.updatedAt").value(
                jdbc.sql("SELECT updated_at FROM scholar_profiles WHERE user_id = 201")
                    .query(LocalDateTime.class).single().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .andExpect(jsonPath("$.meta.path").value("/v1/scholars/201"));

        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 201").query(String.class).single())
            .isEqualTo("수정 이름");
        var profile = jdbc.sql("SELECT * FROM scholar_profiles WHERE user_id = 201").query().singleRow();
        assertThat(profile).containsEntry("phone", "010-9876-5432")
            .containsEntry("school_name", "수정 학교").containsEntry("major", "수정 전공");
        assertThat(jdbc.sql("SELECT updated_at FROM scholar_profiles WHERE user_id = 201")
            .query(LocalDateTime.class).single()).isAfter(before);
        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 202").query(String.class).single())
            .isEqualTo("다른 장학생");
    }

    @Test
    void preservesOmittedFieldsAndStatus() throws Exception {
        mockMvc.perform(patch("/v1/scholars/201").contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"010-9876-5432\"}"))
            .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 201").query(String.class).single())
            .isEqualTo("기존 이름");
        assertThat(jdbc.sql("SELECT * FROM scholar_profiles WHERE user_id = 201").query().singleRow())
            .containsEntry("school_name", "기존 학교").containsEntry("major", "기존 전공")
            .containsEntry("status", "ENROLLED").containsEntry("cohort_id", 106L);
    }

    @Test
    void rollsBackNameWhenProfileUpdateFails() {
        var request = new ScholarUpdateRequest("수정 이름", "1".repeat(21), null, null);
        assertThatThrownBy(() -> repository.update(201, request))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 201").query(String.class).single())
            .isEqualTo("기존 이름");
        assertThat(jdbc.sql("SELECT phone FROM scholar_profiles WHERE user_id = 201")
            .query(String.class).single()).isEqualTo("010-1234-5678");
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 203, 204})
    void rejectsMissingDeletedOrNonScholarAccounts(long id) throws Exception {
        mockMvc.perform(patch("/v1/scholars/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"수정 이름\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SCHOLAR_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"phone\":null}", "{\"phone\":123}",
        "{\"status\":\"LEAVE\"}", "{\"email\":\"new@example.test\"}",
        "{\"name\":\"수정 이름\",\"major\":null}", "{\"name\":\"\"}", "{\"university\":\"\"}"})
    void rejectsUnsupportedOrInvalidBodiesWithoutChanges(String body) throws Exception {
        mockMvc.perform(patch("/v1/scholars/201").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(jdbc.sql("SELECT name FROM users WHERE id = 201").query(String.class).single())
            .isEqualTo("기존 이름");
    }

    @Test
    void rejectsValuesExceedingDatabaseLength() throws Exception {
        mockMvc.perform(patch("/v1/scholars/201").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"수정 이름\",\"phone\":\"" + "1".repeat(21) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidIds(String id) throws Exception {
        mockMvc.perform(patch("/v1/scholars/" + id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"수정 이름\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
