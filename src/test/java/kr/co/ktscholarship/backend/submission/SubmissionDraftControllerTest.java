package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionDraftControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (1801,'임시저장 테스트')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (1801,1801,'1기')").update();
        jdbc.sql("""
            INSERT INTO users (id,name,email,password_hash,role) VALUES
            (1801,'담당자','draft-manager@example.test','test','MANAGER'),
            (1802,'장학생','draft-scholar@example.test','test','SCHOLAR'),
            (1803,'비대상','draft-other@example.test','test','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (1802,1801,'학교'),(1803,1801,'학교')").update();
        jdbc.sql("INSERT INTO request_templates (id,code,name,default_fields) VALUES (1801,'DRAFT_TEST','테스트','[]')").update();
        jdbc.sql("""
            INSERT INTO requests (id,program_id,template_id,title,academic_year,semester,closes_at,status,created_by,published_at)
            VALUES (1801,1801,1801,'테스트',2026,'2','2099-01-01','IN_PROGRESS',1801,'2026-01-01')
            """).update();
        jdbc.sql("INSERT INTO request_targets (request_id,scholar_id) VALUES (1801,1802)").update();
        jdbc.sql("""
            INSERT INTO request_fields (request_id,field_key,label,field_type,required,validation,options) VALUES
            (1801,'name','이름','TEXT',TRUE,NULL,NULL),
            (1801,'gpa','평점','NUMBER',TRUE,'{"min":0,"max":4.5}' FORMAT JSON,NULL),
            (1801,'date','날짜','DATE',FALSE,NULL,NULL),
            (1801,'consent','동의','CHECKBOX',FALSE,NULL,NULL),
            (1801,'choice','선택','SELECT_ONE',FALSE,NULL,'[{"value":"A","label":"가"},{"value":"B","label":"나"}]' FORMAT JSON),
            (1801,'choices','복수 선택','SELECT_MULTI',FALSE,NULL,'["A","B"]' FORMAT JSON),
            (1801,'file','파일','FILE',TRUE,NULL,NULL)
            """).update();
    }

    @Test
    void createsEmptyDraftAndThenReplacesSnapshotWithIncrementedVersion() throws Exception {
        save("{\"answers\":{},\"version\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.success.data.version").value(1))
            .andExpect(jsonPath("$.success.data.savedAt").value(org.hamcrest.Matchers.matchesPattern(".*T.*Z")));
        save("{\"answers\":{\"name\":\"홍길동\",\"gpa\":3.5},\"version\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.version").value(2));
        var response = save("{\"answers\":{\"name\":null},\"version\":2}").andExpect(status().isOk()).andReturn();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submissions WHERE request_id=1801").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT answers FROM submissions WHERE request_id=1801").query(String.class).single()).contains("name").doesNotContain("gpa");
        var dbTime = jdbc.sql("SELECT updated_at FROM submissions WHERE request_id=1801").query(LocalDateTime.class).single();
        assertThat(json.readTree(response.getResponse().getContentAsString()).at("/success/data/savedAt").asText())
            .isEqualTo(dbTime.toInstant(java.time.ZoneOffset.UTC).toString());
        save("{\"answers\":{},\"version\":0}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.sql("SELECT updated_at FROM submissions WHERE request_id=1801").query(LocalDateTime.class).single()).isEqualTo(dbTime);
    }

    @ParameterizedTest
    @ValueSource(strings={"{\"name\":5}","{\"gpa\":\"3\"}","{\"gpa\":4.6}","{\"gpa\":-1}","{\"date\":\"2026-02-30\"}","{\"consent\":\"true\"}","{\"choice\":\"C\"}","{\"choices\":[\"C\"]}","{\"choices\":\"A\"}","{\"unknown\":1}","{\"file\":[1]}","{\"file\":null}"})
    void rejectsInvalidFieldsWithoutCreatingSubmission(String answers) throws Exception {
        save("{\"answers\":"+answers+",\"version\":0}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.details.fieldErrors[0].key").isString())
            .andExpect(jsonPath("$.error.details.fieldErrors[0].message").isString());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submissions WHERE request_id=1801").query(Integer.class).single()).isZero();
    }

    @Test
    void acceptsTypedPartialValuesAndNullWithoutRequiredCheck() throws Exception {
        save("""
            {"answers":{"gpa":4.5,"date":"2026-11-12","consent":false,"choice":"A","choices":["A","B"],"name":null},"version":0}
            """).andExpect(status().isOk());
    }

    @Test
    void persistsJsonObjectAndUnicodeWithoutStringWrapping() throws Exception {
        save("{\"answers\":{\"name\":\"한글 \\\"메모\\\"\",\"gpa\":3.5,\"consent\":false,\"choices\":[\"A\"]},\"version\":0}")
            .andExpect(status().isOk());
        var stored = json.readTree(jdbc.sql("SELECT answers FROM submissions WHERE request_id=1801").query(String.class).single());
        assertThat(stored.isObject()).isTrue();
        assertThat(stored.get("name").asText()).isEqualTo("한글 \"메모\"");
        assertThat(stored.get("gpa").asDouble()).isEqualTo(3.5);
        assertThat(stored.get("consent").isBoolean()).isTrue();
        assertThat(stored.get("choices").isArray()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"SUBMITTED","RESUBMITTED","CONFIRMED"})
    void rejectsCompletedSubmissions(String state) throws Exception {
        existing(state);
        save("{\"answers\":{},\"version\":4}").andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("SUBMISSION_LOCKED"));
        assertThat(jdbc.sql("SELECT version FROM submissions WHERE id=1801").query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void revisionPreservesStateAndUntouchedAnswersAndAllowsUnchangedLockedNumbers() throws Exception {
        existing("REVISION_REQUIRED");
        revision();
        save("{\"answers\":{\"name\":\"보완\",\"gpa\":3},\"version\":4}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.status").value("REVISION_REQUIRED"))
            .andExpect(jsonPath("$.success.data.version").value(5));
        save("{\"answers\":{\"name\":\"재보완\"},\"version\":5}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT answers FROM submissions WHERE id=1801").query(String.class).single()).contains("gpa");
        save("{\"answers\":{\"gpa\":4},\"version\":6}").andExpect(status().is(422));
    }

    @Test
    void revisionWithoutUnresolvedRequestIsLocked() throws Exception {
        existing("REVISION_REQUIRED");
        save("{\"answers\":{},\"version\":4}").andExpect(status().is(422));
    }

    @Test
    void onlyLatestUnresolvedRevisionControlsEditableFields() throws Exception {
        existing("REVISION_REQUIRED");
        revision();
        jdbc.sql("""
            INSERT INTO revision_requests (id,submission_id,reason,requested_by,requested_at,resolved_at) VALUES
            (1802,1801,'최신 미해결',1801,'2090-01-01',NULL),
            (1803,1801,'이미 해결',1801,'2091-01-01','2091-01-02')
            """).update();
        jdbc.sql("INSERT INTO revision_request_fields (revision_request_id,field_key) VALUES (1802,'gpa'),(1803,'name')").update();
        save("{\"answers\":{\"name\":\"수정\"},\"version\":4}").andExpect(status().is(422));
        save("{\"answers\":{\"gpa\":4},\"version\":4}").andExpect(status().isOk());
    }

    @Test
    void rejectsDeadlineAndClosedRequest() throws Exception {
        jdbc.sql("UPDATE requests SET closes_at='2020-01-01' WHERE id=1801").update();
        save("{\"answers\":{},\"version\":0}").andExpect(status().is(422));
        jdbc.sql("UPDATE requests SET closes_at='2099-01-01', status='CLOSED' WHERE id=1801").update();
        save("{\"answers\":{},\"version\":0}").andExpect(status().is(422));
    }

    @Test
    void hidesUnpublishedRequestsAndChecksTargetAndAuthentication() throws Exception {
        mvc.perform(put("/v1/me/submissions/1801/draft").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/v1/me/submissions/1801/draft").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(j->j.subject("1801")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isForbidden());
        mvc.perform(put("/v1/me/submissions/1801/draft").contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{},\"version\":0}")
            .with(jwt().jwt(j->j.subject("1803")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_A_TARGET"));
        jdbc.sql("UPDATE requests SET published_at=NULL WHERE id=1801").update();
        save("{\"answers\":{},\"version\":0}").andExpect(status().isNotFound());
        jdbc.sql("UPDATE requests SET published_at='2026-01-01',status='DRAFT' WHERE id=1801").update();
        save("{\"answers\":{},\"version\":0}").andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"answers\":{},\"version\":-1}","{\"answers\":{},\"version\":1.1}","{\"answers\":{},\"version\":\"0\"}","{\"answers\":[],\"version\":0}"})
    void rejectsMalformedEnvelope(String body) throws Exception {
        save(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void nonzeroFirstVersionDoesNotInsert() throws Exception {
        save("{\"answers\":{},\"version\":1}").andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submissions WHERE request_id=1801").query(Integer.class).single()).isZero();
    }

    private ResultActions save(String body) throws Exception {
        return mvc.perform(put("/v1/me/submissions/1801/draft").contentType(MediaType.APPLICATION_JSON).content(body)
            .with(jwt().jwt(j->j.subject("1802")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))));
    }

    private void existing(String state) {
        jdbc.sql("""
            INSERT INTO submissions (id,request_id,scholar_id,status,answers,version)
            VALUES (1801,1801,1802,:state,'{"name":"기존","gpa":3.0}' FORMAT JSON,4)
            """).param("state",state).update();
    }

    private void revision() {
        jdbc.sql("INSERT INTO revision_requests (id,submission_id,reason,requested_by) VALUES (1801,1801,'이름 보완',1801)").update();
        jdbc.sql("INSERT INTO revision_request_fields (revision_request_id,field_key) VALUES (1801,'name')").update();
    }
}
