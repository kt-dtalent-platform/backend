package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class TargetDeadlineControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    MockMvc mvc;
    Instant past, future;
    @BeforeEach void seed() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        new WorkflowFixture(jdbc,source).seed();
        past=Instant.now().minus(1,ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        future=Instant.now().plus(2,ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.sql("UPDATE requests SET closes_at=:past WHERE id IN (71001,71002)").param("past",UtcDateTimes.toDb(past)).update();
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,long user,String role) {
        return request.with(jwt().jwt(j->j.subject(Long.toString(user))).authorities(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    MockHttpServletRequestBuilder extend(long request,long user,Instant due) {
        return as(patch("/v1/requests/{r}/targets/{s}/deadline",request,user).contentType("application/json")
            .content("{\"dueAt\":\""+due+"\"}"),70001,"MANAGER");
    }
    void grant(long request) throws Exception { mvc.perform(extend(request,70002,future)).andExpect(status().isOk()); }
    @Test void extendsOnlySelectedTargetAndPreservesCommonDeadline() throws Exception {
        grant(71001);
        mvc.perform(as(get("/v1/requests/71001/targets/70002/deadline"),70001,"MANAGER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.commonDueAt").value(past.toString()))
            .andExpect(jsonPath("$.success.data.dueAt").value(future.toString()));
        assertThat(jdbc.sql("SELECT closes_at FROM requests WHERE id=71001").query(java.time.LocalDateTime.class).single())
            .isEqualTo(UtcDateTimes.toDb(past));
        assertThat(jdbc.sql("SELECT extended_due_at FROM request_targets WHERE request_id=71001 AND scholar_id=70003").query().singleRow())
            .containsEntry("extended_due_at",null);
    }
    @Test void tasksFormAndDraftUseTheSamePersonalDeadline() throws Exception {
        grant(71001);
        mvc.perform(as(get("/v1/me/tasks?status=ALL"),70002,"SCHOLAR")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items[?(@.requestId==71001)].dueAt").value(org.hamcrest.Matchers.contains(future.toString())));
        mvc.perform(as(get("/v1/me/submissions/71001"),70002,"SCHOLAR")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.request.requestDueAt").value(future.toString()));
        mvc.perform(as(put("/v1/me/submissions/71001/draft").contentType("application/json")
            .content("{\"answers\":{\"name\":\"학생\"},\"version\":2}"),70002,"SCHOLAR")).andExpect(status().isOk());
        mvc.perform(as(put("/v1/me/submissions/71001/draft").contentType("application/json")
            .content("{\"answers\":{},\"version\":0}"),70003,"SCHOLAR")).andExpect(status().isOk());
    }
    @Test void finalSubmissionMarksOnlyTheUnextendedStudentLate() throws Exception {
        grant(71001);
        jdbc.sql("UPDATE submission_files SET upload_status='UPLOADED' WHERE id=73001").update();
        mvc.perform(as(post("/v1/me/submissions/71001/submit").contentType("application/json")
            .content("{\"answers\":{\"name\":\"학생\",\"gpa\":3.8},\"version\":2}"),70002,"SCHOLAR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.status").value("SUBMITTED"));
        mvc.perform(as(post("/v1/me/submissions/71001/submit").contentType("application/json")
            .content("{\"answers\":{\"name\":\"다른학생\",\"gpa\":3.5},\"version\":0}"),70003,"SCHOLAR")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT is_late FROM submissions WHERE id=72001").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT is_late FROM submissions WHERE id=72002").query(Boolean.class).single()).isTrue();
    }
    @Test void resubmissionUsesExtendedDeadlineAndPreservesFirstSubmission() throws Exception {
        grant(71002);
        jdbc.sql("UPDATE submissions SET status='REVISION_REQUIRED' WHERE id=72003").update();
        jdbc.sql("INSERT INTO revision_requests(id,submission_id,reason,requested_by) VALUES(85001,72003,'검증',70001)").update();
        jdbc.sql("INSERT INTO revision_request_fields(revision_request_id,field_key) VALUES(85001,'gpa')").update();
        jdbc.sql("""
            INSERT INTO submission_files(id,submission_id,field_key,s3_key,original_name,content_type,size_bytes,upload_status)
            VALUES(85002,72003,'receipt','deadline/test','검증.pdf','application/pdf',123,'UPLOADED')
            """).update();
        mvc.perform(as(post("/v1/me/submissions/71002/resubmit").contentType("application/json")
            .content("{\"answers\":{\"gpa\":3.8},\"version\":1}"),70002,"SCHOLAR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.status").value("RESUBMITTED"))
            .andExpect(jsonPath("$.success.data.submittedAt").value("2026-11-01T00:00:00Z"));
    }
    @Test void expiredExtensionAllowsLateDraftButClosedRequestCannotBeExtended() throws Exception {
        grant(71001);
        jdbc.sql("UPDATE request_targets SET extended_due_at=:past WHERE request_id=71001 AND scholar_id=70002")
            .param("past",UtcDateTimes.toDb(Instant.now().minusSeconds(1))).update();
        mvc.perform(as(put("/v1/me/submissions/71001/draft").contentType("application/json")
            .content("{\"answers\":{},\"version\":2}"),70002,"SCHOLAR")).andExpect(status().isOk());
        mvc.perform(extend(71004,70002,future.plus(100,ChronoUnit.DAYS))).andExpect(status().isUnprocessableEntity());
    }
    @Test void rejectsPastShortenedMissingAndUnauthorizedChanges() throws Exception {
        mvc.perform(extend(71001,70002,past)).andExpect(status().isBadRequest());
        grant(71001);
        mvc.perform(extend(71001,70002,future.minusSeconds(1))).andExpect(status().isBadRequest());
        mvc.perform(extend(71001,99999,future)).andExpect(status().isNotFound());
        mvc.perform(patch("/v1/requests/71001/targets/70002/deadline").contentType("application/json")
            .content("{\"dueAt\":\""+future+"\"}")).andExpect(status().isUnauthorized());
        mvc.perform(as(patch("/v1/requests/71001/targets/70002/deadline").contentType("application/json")
            .content("{\"dueAt\":\""+future+"\"}"),70002,"SCHOLAR")).andExpect(status().isForbidden());
    }
}
