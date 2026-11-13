package kr.co.ktscholarship.backend.report;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class ManagerDashboardTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    WorkflowFixture fixture;MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source);fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup(){fixture.clean();}
    @Test void countsTargetsOnceAndIncludesEmptyRequests() throws Exception {
        mvc.perform(get("/v1/dashboard/manager?programId=70001").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.review.waiting").value(1))
            .andExpect(jsonPath("$.success.data.review.oldestSubmissionId").value(72003))
            .andExpect(jsonPath("$.success.data.review.oldestSubmittedAt").value("2026-11-01T00:00:00Z"))
            .andExpect(jsonPath("$.success.data.requests.length()").value(3))
            .andExpect(jsonPath("$.success.data.requests[0].targetCount").value(2))
            .andExpect(jsonPath("$.success.data.requests[0].notSubmitted").value(2))
            .andExpect(jsonPath("$.success.data.requests[1].targetCount").value(0));
    }
    @Test void countsAllStatesAndUsesCurrentTargetPairsForOldest() throws Exception {
        fixture.sql("UPDATE submissions SET status='CONFIRMED' WHERE id=72001");
        fixture.sql("UPDATE submissions SET status='RESUBMITTED',updated_at='2026-11-02',submitted_at='2026-10-01' WHERE id=72002");
        fixture.sql("INSERT INTO submissions(request_id,scholar_id,status,answers) VALUES(71002,70003,'REVISION_REQUIRED','{}' FORMAT JSON)");
        mvc.perform(get("/v1/dashboard/manager?cohortId=70001").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(jsonPath("$.success.data.review.waiting").value(2))
            .andExpect(jsonPath("$.success.data.review.resubmitted").value(1))
            .andExpect(jsonPath("$.success.data.review.oldestSubmissionId").value(72003))
            .andExpect(jsonPath("$.success.data.requests[0].confirmed").value(1))
            .andExpect(jsonPath("$.success.data.requests[0].waiting").value(1))
            .andExpect(jsonPath("$.success.data.requests[0].notSubmitted").value(0))
            .andExpect(jsonPath("$.success.data.requests[1].revisionRequired").value(1));
        fixture.sql("DELETE FROM request_targets WHERE request_id=71002 AND scholar_id=70002");
        mvc.perform(get("/v1/dashboard/manager?cohortId=70001").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(jsonPath("$.success.data.review.oldestSubmissionId").value(72002));
    }
    @Test void validatesFiltersAndRestrictsRole() throws Exception {
        mvc.perform(get("/v1/dashboard/manager?programId=99999").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.requests").isEmpty())
            .andExpect(jsonPath("$.success.data.review.waiting").value(0)).andExpect(jsonPath("$.success.data.review.oldestSubmissionId").isEmpty());
        mvc.perform(get("/v1/dashboard/manager?cohortId=0").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/dashboard/manager")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/dashboard/manager").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")))).andExpect(status().isForbidden());
    }
}
