package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import java.time.Instant;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RequestDeadlineClosureTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    @Autowired RequestDeadlineClosureRepository repository;
    private final Instant now=Instant.parse("2026-11-27T00:00:00Z");

    @BeforeEach void seed() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity()).build();
        new WorkflowFixture(jdbc,source).seed();
        jdbc.sql("UPDATE requests SET closes_at=:due WHERE id BETWEEN 71001 AND 71005")
            .param("due",UtcDateTimes.toDb(now)).update();
    }
    String requestStatus(long id) {
        return jdbc.sql("SELECT status FROM requests WHERE id=:id").param("id",id).query(String.class).single();
    }
    @Test void closesOnlyPublishedExpiredRequestsAndIsIdempotent() {
        jdbc.sql("UPDATE requests SET closes_at=:due WHERE id=71002")
            .param("due",UtcDateTimes.toDb(now.plusSeconds(1))).update();
        jdbc.sql("UPDATE submissions SET status='CONFIRMED' WHERE request_id=71001").update();
        assertThat(repository.closeExpired(now)).isEqualTo(2);
        assertThat(requestStatus(71001)).isEqualTo("CLOSED");
        assertThat(requestStatus(71005)).isEqualTo("CLOSED");
        assertThat(requestStatus(71002)).isEqualTo("IN_PROGRESS");
        assertThat(requestStatus(71003)).isEqualTo("DRAFT");
        assertThat(repository.closeExpired(now)).isZero();
    }
    @Test void waitsForLastIndividualOrRevisionDeadline() {
        jdbc.sql("UPDATE request_targets SET extended_due_at=:due WHERE request_id=71001 AND scholar_id=70003")
            .param("due",UtcDateTimes.toDb(now.plusSeconds(60))).update();
        // 보완 요청도 같은 extended_due_at에 기한을 저장한다.
        jdbc.sql("UPDATE submissions SET status='REVISION_REQUIRED' WHERE id=72002").update();
        repository.closeExpired(now);
        assertThat(requestStatus(71001)).isEqualTo("IN_PROGRESS");
        jdbc.sql("UPDATE submissions SET status='CONFIRMED' WHERE request_id=71001").update();
        repository.closeExpired(now.plusSeconds(60));
        assertThat(requestStatus(71001)).isEqualTo("CLOSED");
    }
    @Test void pendingWorkKeepsRequestOpenEvenAfterAllDeadlines() {
        for(String state:new String[]{"DRAFT","SUBMITTED","RESUBMITTED","REVISION_REQUIRED"}) {
            jdbc.sql("UPDATE submissions SET status=:state WHERE request_id=71001").param("state",state).update();
            repository.closeExpired(now);
            assertThat(requestStatus(71001)).isEqualTo("IN_PROGRESS");
        }
        jdbc.sql("UPDATE submissions SET status='CONFIRMED' WHERE request_id=71001").update();
        jdbc.sql("UPDATE submissions SET status='CONFIRMED' WHERE request_id=71002").update();
        repository.closeExpired(now);
        assertThat(requestStatus(71002)).isEqualTo("IN_PROGRESS");
    }
    @Test void extensionDoesNotCloseRequestBeforeCommonDeadline() {
        jdbc.sql("UPDATE requests SET closes_at=:due WHERE id=71001")
            .param("due",UtcDateTimes.toDb(now.plusSeconds(120))).update();
        jdbc.sql("UPDATE request_targets SET extended_due_at=:due WHERE request_id=71001")
            .param("due",UtcDateTimes.toDb(now.minusSeconds(1))).update();
        repository.closeExpired(now);
        assertThat(requestStatus(71001)).isEqualTo("IN_PROGRESS");
    }
}
