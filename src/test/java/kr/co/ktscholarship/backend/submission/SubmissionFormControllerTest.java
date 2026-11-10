package kr.co.ktscholarship.backend.submission;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionFormControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    private MockMvc mvc;

    private RequestPostProcessor scholar(long id) {
        return jwt().jwt(j -> j.subject(Long.toString(id)))
            .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (91,'검증 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (96,91,'6기')").update();
        jdbc.sql("""
            INSERT INTO users (id,email,password_hash,name,role) VALUES
                (910,'manager@form.test','test-only','담당자','MANAGER'),
                (911,'scholar@form.test','test-only','장학생','SCHOLAR'),
                (912,'other@form.test','test-only','다른 장학생','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (911,96,'학교'),(912,96,'학교')").update();
        jdbc.sql("INSERT INTO request_templates (id,code,name,default_fields) VALUES (91,'FORM_HTTP_TEST','검증','[]' FORMAT JSON)").update();
        jdbc.sql("""
            INSERT INTO requests (id,program_id,template_id,title,academic_year,semester,closes_at,status,created_by,published_at)
            VALUES (931,91,91,'공개 폼',2026,'2','2026-12-01','IN_PROGRESS',910,'2026-11-01'),
                (932,91,91,'공개 전',2026,'2','2026-12-01','DRAFT',910,NULL),
                (933,91,91,'다른 학생 폼',2026,'2','2026-12-01','IN_PROGRESS',910,'2026-11-01')
            """).update();
        jdbc.sql("INSERT INTO request_targets (id,request_id,scholar_id) VALUES (941,931,911),(942,931,912),(943,933,912)").update();
        jdbc.sql("""
            INSERT INTO submissions (id,request_id,scholar_id,status,answers,version) VALUES
                (951,931,911,'DRAFT','{"value":"본인 입력"}' FORMAT JSON,1),
                (952,931,912,'DRAFT','{"value":"다른 학생 입력"}' FORMAT JSON,2)
            """).update();
    }

    @Test
    void returnsOnlyTokenOwnersSubmissionEvenWithAnotherScholarId() throws Exception {
        mvc.perform(get("/v1/me/submissions/931?scholarId=912&userId=912").with(scholar(911)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.request.id").value(931))
            .andExpect(jsonPath("$.success.data.submission.id").value(951))
            .andExpect(jsonPath("$.success.data.submission.answers.value").value("본인 입력"))
            .andExpect(jsonPath("$.success.data.submission.version").value(1))
            .andExpect(jsonPath("$.meta.path").value("/v1/me/submissions/931"));
        mvc.perform(get("/v1/me/submissions/931?scholarId=911").with(scholar(912)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.submission.id").value(952))
            .andExpect(jsonPath("$.success.data.submission.answers.value").value("다른 학생 입력"));
    }

    @Test
    void rejectsRequestsOutsideOwnTargets() throws Exception {
        mvc.perform(get("/v1/me/submissions/933?scholarId=912").with(scholar(911)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_A_TARGET"));
    }

    @ParameterizedTest
    @ValueSource(longs = {932,999})
    void hidesUnpublishedAndMissingRequests(long id) throws Exception {
        mvc.perform(get("/v1/me/submissions/{requestId}",id).with(scholar(911)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0","-1","abc"})
    void rejectsInvalidRequestIds(String id) throws Exception {
        mvc.perform(get("/v1/me/submissions/"+id).with(scholar(911)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void requiresScholarAuthentication() throws Exception {
        mvc.perform(get("/v1/me/submissions/931")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/v1/me/submissions/931").with(jwt().jwt(j -> j.subject("910"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }
}
