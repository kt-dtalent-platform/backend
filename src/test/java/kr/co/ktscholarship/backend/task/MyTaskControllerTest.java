package kr.co.ktscholarship.backend.task;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
class MyTaskControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id, name) VALUES (91, '조회 검증')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (96, 91, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, email, password_hash, name, role) VALUES
                (910, 'manager@tasks.test', 'test-only', '담당자', 'MANAGER'),
                (911, 'scholar@tasks.test', 'test-only', '장학생', 'SCHOLAR'),
                (912, 'other@tasks.test', 'test-only', '다른 장학생', 'SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id, cohort_id, school_name) VALUES (911,96,'학교'), (912,96,'학교')").update();
        jdbc.sql("INSERT INTO request_templates (id, code, name, default_fields) VALUES (91,'TASK_TEST','검증','[]' FORMAT JSON)").update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, template_id, title, academic_year, semester, closes_at, status, created_by, published_at, created_at)
            VALUES (931,91,91,'미제출',2026,'2','2026-12-01','IN_PROGRESS',910,'2026-11-01','2026-11-01'),
                (932,91,91,'확인완료',2026,'2','2026-12-02','IN_PROGRESS',910,'2026-11-01','2026-11-02'),
                (933,91,91,'다른 대상',2026,'2','2026-12-03','IN_PROGRESS',910,'2026-11-01','2026-11-03'),
                (934,91,91,'공개 전',2026,'2','2026-12-04','DRAFT',910,NULL,'2026-11-04'),
                (935,91,91,'마감 요청',2026,'2','2026-12-05','CLOSED',910,'2026-11-01','2026-11-05')
            """).update();
        jdbc.sql("INSERT INTO request_targets (id,request_id,scholar_id) VALUES (941,931,911),(942,932,911),(943,933,912),(944,934,911),(945,935,911)").update();
        jdbc.sql("INSERT INTO submissions (id,request_id,scholar_id,status,answers) VALUES (951,932,911,'CONFIRMED','{}' FORMAT JSON)").update();
    }

    @Test
    void defaultsToOwnTodoAndReturnsCommonResponseAndUtcDday() throws Exception {
        mvc.perform(get("/v1/me/tasks").with(jwt().jwt(j -> j.subject("911"))
                .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(931))
            .andExpect(jsonPath("$.success.data.items[0].status").value("NOT_SUBMITTED"))
            .andExpect(jsonPath("$.success.data.items[0].dueAt").value("2026-12-01T00:00:00"))
            .andExpect(jsonPath("$.success.data.items[0].dDay").value(
                (int) ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), LocalDate.of(2026,12,1))))
            .andExpect(jsonPath("$.meta.path").value("/v1/me/tasks"));
    }

    @Test
    void doneAndAllFiltersUseOwnSubmissionState() throws Exception {
        var scholar = jwt().jwt(j -> j.subject("911")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"));
        mvc.perform(get("/v1/me/tasks?status=DONE").with(scholar)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(932));
        mvc.perform(get("/v1/me/tasks?status=ALL&sort=CREATED_DESC").with(scholar)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items.length()").value(2))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(932))
            .andExpect(jsonPath("$.success.data.items[1].requestId").value(931));
    }

    @Test
    void ignoresClientSuppliedScholarIdAndUsesTokenSubject() throws Exception {
        mvc.perform(get("/v1/me/tasks?scholarId=911").with(jwt().jwt(j -> j.subject("912"))
                .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(933));
    }

    @Test
    void returnsEmptyListForScholarWithoutTargets() throws Exception {
        mvc.perform(get("/v1/me/tasks").with(jwt().jwt(j -> j.subject("999"))
                .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=INVALID", "sort=INVALID"})
    void rejectsUnsupportedFilters(String query) throws Exception {
        mvc.perform(get("/v1/me/tasks?" + query).with(jwt().jwt(j -> j.subject("911"))
                .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void requiresScholarAuthentication() throws Exception {
        mvc.perform(get("/v1/me/tasks")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/v1/me/tasks").with(jwt().jwt(j -> j.subject("910"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }
}
