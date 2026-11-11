package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class ScholarRegistrationIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScholarRegistrationService service;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper mapper;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("INSERT INTO programs (id,name) VALUES (701,'등록 검증')");
        jdbc.update("INSERT INTO cohorts (id,program_id,name) VALUES (706,701,'6기'),(707,701,'7기')");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM scholar_profiles WHERE cohort_id IN (706,707)");
        jdbc.update("DELETE FROM users WHERE email LIKE 'registration-%@example.test'");
        jdbc.update("DELETE FROM cohorts WHERE id IN (706,707)");
        jdbc.update("DELETE FROM programs WHERE id = 701");
    }

    @Test
    void createsLoginReadyAccountAndProfileAndReturnsPasswordOnlyOnce() throws Exception {
        var response = mvc.perform(post("/v1/scholars").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"cohortId":706,"name":"등록 학생","email":"registration-success@example.test",
                 "university":"학교","phone":"010-1234-5678","major":"전공","studentNo":"1234"}
                """).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success.data.accountStatus").value("ACTIVE"))
            .andReturn().getResponse();
        var data = mapper.readTree(response.getContentAsString()).path("success").path("data");
        long id = data.path("id").asLong();
        String password = data.path("temporaryPassword").asText();
        assertThat(password).hasSize(24);
        assertThat(response.getHeader("Location")).isEqualTo("/v1/scholars/" + id);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id);
        assertThat(hash).isNotEqualTo(password);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_change_required FROM users WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT school_name FROM scholar_profiles WHERE user_id=?",String.class,id)).isEqualTo("학교");
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"registration-success@example.test\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void duplicateAcrossCohortsIs409ButDeletedEmailCanBeReused() throws Exception {
        var first = service.register(request(706,"registration-reuse@example.test","학교"));
        mvc.perform(post("/v1/scholars").contentType(MediaType.APPLICATION_JSON)
            .content("{\"cohortId\":707,\"name\":\"학생\",\"email\":\"registration-reuse@example.test\",\"university\":\"학교\"}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", first.id());
        var second = service.register(request(707,"registration-reuse@example.test","학교"));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.temporaryPassword()).isNotEqualTo(first.temporaryPassword());
    }

    @Test
    void blankUniversityIs400WithoutCreatingAccount() throws Exception {
        mvc.perform(post("/v1/scholars").contentType(MediaType.APPLICATION_JSON)
            .content("{\"cohortId\":706,\"name\":\"학생\",\"email\":\"registration-invalid@example.test\",\"university\":\" \"}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='registration-invalid@example.test'",Long.class)).isZero();
    }

    @Test
    void profileFailureRollsBackAccountAndUnknownCohortCreatesNothing() {
        assertThatThrownBy(() -> service.register(request(706,"registration-rollback@example.test",null)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='registration-rollback@example.test'",Long.class)).isZero();
        assertThatThrownBy(() -> service.register(request(999999,"registration-missing@example.test","학교")))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COHORT_NOT_FOUND));
    }

    private ScholarCreateRequest request(long cohort, String email, String university) {
        return new ScholarCreateRequest(cohort,"학생",email,null,university,null,null);
    }
}
