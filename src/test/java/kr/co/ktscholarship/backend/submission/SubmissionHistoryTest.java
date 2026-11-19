package kr.co.ktscholarship.backend.submission;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class SubmissionHistoryTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    WorkflowFixture fixture; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source);fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup(){fixture.clean();}
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,long id,String role) {
        return request.with(jwt().jwt(j->j.subject(Long.toString(id))).authorities(new SimpleGrantedAuthority("ROLE_"+role)));
    }

    @Test void historyOnlyReturnsOwnNonDraftRowsWithConsistentPagination() throws Exception {
        fixture.sql("INSERT INTO submissions(request_id,scholar_id,status,answers,submitted_at,confirmed_at) VALUES(71005,70002,'CONFIRMED','{}' FORMAT JSON,'2026-11-03','2026-11-04')");
        mvc.perform(as(get("/v1/me/submissions?page=1&page_size=1&scholarId=70003"),70002,"SCHOLAR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.total").value(2))
            .andExpect(jsonPath("$.success.data.pageSize").value(1)).andExpect(jsonPath("$.success.data.items.length()").value(1))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(71005))
            .andExpect(jsonPath("$.success.data.items[0].purged").value(false))
            .andExpect(jsonPath("$.success.data.items[0].confirmedAt").value("2026-11-04T00:00:00Z"));
        mvc.perform(as(get("/v1/me/submissions?page=2&page_size=1"),70002,"SCHOLAR"))
            .andExpect(jsonPath("$.success.data.items[0].requestId").value(71002));
        mvc.perform(as(get("/v1/me/submissions?status=CONFIRMED"),70002,"SCHOLAR"))
            .andExpect(jsonPath("$.success.data.total").value(1));
        mvc.perform(as(get("/v1/me/submissions"),70003,"SCHOLAR"))
            .andExpect(jsonPath("$.success.data.total").value(0)).andExpect(jsonPath("$.success.data.items").isEmpty());
    }
    @Test void historyRejectsInvalidFiltersAndManagerRole() throws Exception {
        for(String q:new String[]{"status=INVALID","page=0","page_size=101"})
            mvc.perform(as(get("/v1/me/submissions?"+q),70002,"SCHOLAR")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/v1/me/submissions"),70001,"MANAGER")).andExpect(status().isForbidden());
    }
}
