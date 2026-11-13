package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import javax.sql.DataSource;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class SubmissionFinalTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    @Autowired JsonMapper json;
    @MockitoSpyBean SubmissionFinalRepository repository;
    WorkflowFixture fixture; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source); fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() { fixture.clean(); }
    ResultActions send(long request,String action,String body,long user) throws Exception {
        return mvc.perform(post("/v1/me/submissions/"+request+"/"+action).contentType(MediaType.APPLICATION_JSON).content(body)
            .with(jwt().jwt(j->j.subject(Long.toString(user))).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))));
    }
    void uploaded() { fixture.sql("UPDATE submission_files SET upload_status='UPLOADED' WHERE id=73001"); }
    int historyCount() { return jdbc.sql("SELECT COUNT(*) FROM submission_histories WHERE submission_id=72001").query(Integer.class).single(); }

    @Test void submitsAndPreservesPreviousSnapshotWithUtcTime() throws Exception {
        uploaded();
        send(71001,"submit","{\"answers\":{\"name\":\"수정 이름\",\"gpa\":4.0},\"version\":2}",70002)
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.success.data.version").value(3))
            .andExpect(jsonPath("$.success.data.completionMessage").value("제출 감사합니다"))
            .andExpect(jsonPath("$.success.data.submittedAt").value(org.hamcrest.Matchers.endsWith("Z")));
        assertThat(jdbc.sql("SELECT submit_count FROM submissions WHERE id=72001").query(Integer.class).single()).isEqualTo(1);
        var snapshot=json.readTree(jdbc.sql("SELECT snapshot FROM submission_histories WHERE submission_id=72001").query(String.class).single());
        assertThat(snapshot.at("/answers/name").asText()).isEqualTo("홍길동");
        assertThat(snapshot.at("/files/0/id").asLong()).isEqualTo(73001);
        send(71001,"submit","{\"answers\":{},\"version\":3}",70002).andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("ALREADY_SUBMITTED"));
        assertThat(historyCount()).isOne();
    }
    @Test void collectsAllErrorsAndDoesNotSubmitPendingOrOtherOwnersFiles() throws Exception {
        send(71001,"submit","{\"answers\":{\"name\":\"\",\"gpa\":99},\"version\":2}",70002)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.details.fieldErrors[*].key",org.hamcrest.Matchers.containsInAnyOrder("name","gpa","receipt")));
        fixture.sql("UPDATE submission_files SET upload_status='DELETED' WHERE id=73001");
        send(71001,"submit","{\"answers\":{\"name\":\"홍길동\",\"gpa\":3.5},\"version\":2}",70002)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fieldErrors[0].key").value("receipt"));
        assertThat(historyCount()).isZero();
    }
    @Test void versionConflictAndDeadlineDoNotWriteAnything() throws Exception {
        uploaded();
        send(71001,"submit","{\"answers\":{},\"version\":1}",70002).andExpect(status().isConflict());
        fixture.sql("UPDATE requests SET closes_at='2020-01-01' WHERE id=71001");
        send(71001,"submit","{\"answers\":{},\"version\":2}",70002).andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("DEADLINE_PASSED"));
        assertThat(historyCount()).isZero();
    }
    @Test void supportsFirstSubmissionWithoutDraftWhenNoRequiredFile() throws Exception {
        fixture.sql("UPDATE request_fields SET required=FALSE WHERE request_id=71002 AND field_type='FILE'");
        send(71002,"submit","{\"answers\":{\"name\":\"첫 제출\",\"gpa\":3.0},\"version\":0}",70003)
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.version").value(1));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submissions WHERE request_id=71002 AND scholar_id=70003").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission_histories WHERE actor_id=70003 AND from_status IS NULL").query(Integer.class).single()).isOne();
    }
    @Test void revisionMergesOnlyEditableKeysAndResolvesOnlyLatestRequest() throws Exception {
        uploaded();
        fixture.sql("UPDATE submissions SET status='REVISION_REQUIRED',version=4,submit_count=1,submitted_at='2026-11-01' WHERE id=72001");
        fixture.sql("INSERT INTO revision_requests(id,submission_id,reason,requested_by,requested_at) VALUES(74001,72001,'이전',70001,'2026-01-01'),(74002,72001,'최신',70001,'2026-02-01')");
        fixture.sql("INSERT INTO revision_request_fields(revision_request_id,field_key) VALUES(74001,'gpa'),(74002,'name')");
        send(71001,"resubmit","{\"answers\":{\"gpa\":4.0},\"version\":4}",70002).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("FIELD_NOT_EDITABLE"));
        send(71001,"resubmit","{\"answers\":{\"name\":\"보완 이름\"},\"version\":4}",70002).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.status").value("RESUBMITTED"))
            .andExpect(jsonPath("$.success.data.version").value(5))
            .andExpect(jsonPath("$.success.data.submittedAt").value("2026-11-01T00:00:00Z"))
            .andExpect(jsonPath("$.success.data.resubmittedAt").value(org.hamcrest.Matchers.endsWith("Z")));
        var answers=json.readTree(jdbc.sql("SELECT answers FROM submissions WHERE id=72001").query(String.class).single());
        assertThat(answers.get("gpa").asDouble()).isEqualTo(3.5);
        assertThat(answers.get("name").asText()).isEqualTo("보완 이름");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM revision_requests WHERE id=74002 AND resolved_at IS NOT NULL").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM revision_requests WHERE id=74001 AND resolved_at IS NULL").query(Integer.class).single()).isOne();
        assertThat(historyCount()).isOne();
    }
    @Test void rejectsInvalidStateMissingRequestAndOtherUsersScope() throws Exception {
        send(71001,"resubmit","{\"answers\":{},\"version\":2}",70002).andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("NOT_IN_REVISION"));
        send(71001,"submit","{\"answers\":{},\"version\":0}",79999).andExpect(status().isForbidden());
        send(71003,"submit","{\"answers\":{},\"version\":0}",70002).andExpect(status().isNotFound());
        send(79999,"submit","{\"answers\":{},\"version\":0}",70002).andExpect(status().isNotFound());
        mvc.perform(post("/v1/me/submissions/71001/submit").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/me/submissions/71001/submit").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")))).andExpect(status().isForbidden());
    }
    @Test void failedHistoryWriteRollsBackStateAndVersion() throws Exception {
        uploaded();
        doThrow(new IllegalStateException("test-only history failure")).when(repository)
            .history(anyLong(),anyString(),anyString(),anyLong(),anyMap(),anyList(),any());
        send(71001,"submit","{\"answers\":{\"name\":\"수정\",\"gpa\":4.0},\"version\":2}",70002)
            .andExpect(status().isInternalServerError());
        assertThat(jdbc.sql("SELECT status FROM submissions WHERE id=72001").query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT version FROM submissions WHERE id=72001").query(Integer.class).single()).isEqualTo(2);
        assertThat(historyCount()).isZero();
    }
}
