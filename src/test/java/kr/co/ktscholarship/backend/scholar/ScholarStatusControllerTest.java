package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScholarStatusControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;
    MockMvc mvc;

    @BeforeEach void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs(id,name) VALUES(80001,'상태 검증')").update();
        jdbc.sql("INSERT INTO cohorts(id,program_id,name) VALUES(80001,80001,'2026')").update();
        jdbc.sql("""
            INSERT INTO users(id,name,email,password_hash,role,deleted_at) VALUES
            (80001,'담당자','status-manager@example.test','test','MANAGER',NULL),
            (80002,'학생A','status-a@example.test','test','SCHOLAR',NULL),
            (80003,'학생B','status-b@example.test','test','SCHOLAR',NULL),
            (80004,'탈퇴','status-deleted@example.test','test','SCHOLAR','2026-01-01')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles(user_id,cohort_id,school_name)
            SELECT id,80001,'학교' FROM users WHERE id BETWEEN 80001 AND 80004
            """).update();
    }
    MockHttpServletRequestBuilder change(long id, String body) {
        return patch("/v1/scholars/{id}/status",id).contentType(MediaType.APPLICATION_JSON).content(body)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
    MockHttpServletRequestBuilder bulk(String body) {
        return patch("/v1/scholars/status").contentType(MediaType.APPLICATION_JSON).content(body)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    @Test void completesAndReturnsUtcTimestampAndDetails() throws Exception {
        mvc.perform(change(80002,"{\"status\":\"COMPLETED\",\"completedYear\":2026}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.id").value(80002))
            .andExpect(jsonPath("$.success.data.updatedAt").value(org.hamcrest.Matchers.endsWith("Z")));
        mvc.perform(get("/v1/scholars/80002").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.completedYear").value(2026));
        mvc.perform(get("/v1/scholars?status=COMPLETED").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items[0].completedYear").value(2026));
    }
    @Test void supportsLeaveAndClearsPreviousWithdrawalDetails() throws Exception {
        mvc.perform(change(80002,"{\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"OTHER\",\"withdrawalReasonDetail\":\"  개인 사정  \"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT withdrawal_reason_detail FROM scholar_profiles WHERE user_id=80002").query(String.class).single()).isEqualTo("개인 사정");
        mvc.perform(change(80002,"{\"status\":\"LEAVE\"}")).andExpect(status().isOk());
        var row=jdbc.sql("SELECT * FROM scholar_profiles WHERE user_id=80002").query().singleRow();
        assertThat(row).containsEntry("status","LEAVE").containsEntry("withdrawal_reason",null)
            .containsEntry("withdrawal_reason_detail",null).containsEntry("completed_year",null);
        assertThat(accountStatus(80002)).isEqualTo("ACTIVE");
    }

    @Test void withdrawalBlocksLoginAndReturningToEnrolledAllowsLogin() throws Exception {
        jdbc.sql("UPDATE users SET password_hash=:hash WHERE id=80002")
            .param("hash",encoder.encode("test-password-123")).update();
        mvc.perform(change(80002,"{\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"VOLUNTARY\"}"))
            .andExpect(status().isOk());
        assertThat(accountStatus(80002)).isEqualTo("INACTIVE");
        mvc.perform(get("/v1/scholars/80002").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(jsonPath("$.success.data.status").value("WITHDRAWN"))
            .andExpect(jsonPath("$.success.data.accountStatus").value("INACTIVE"));
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"status-a@example.test\",\"password\":\"test-password-123\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        mvc.perform(change(80002,"{\"status\":\"ENROLLED\"}")).andExpect(status().isOk());
        // 테스트의 공용 트랜잭션 캐시를 비워 실제 별도 로그인 요청과 같은 상태로 확인한다.
        entityManager.clear();
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"status-a@example.test\",\"password\":\"test-password-123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.accessToken").isNotEmpty());
    }

    @Test void completionKeepsLoginEnabled() throws Exception {
        mvc.perform(change(80002,"{\"status\":\"COMPLETED\",\"completedYear\":2026}"))
            .andExpect(status().isOk());
        assertThat(accountStatus(80002)).isEqualTo("ACTIVE");
    }

    @Test void synchronizesAccountsInBulkAndRepairsAnUnchangedScholarStatus() throws Exception {
        String body="{\"scholarIds\":[80002,80003],\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"VOLUNTARY\"}";
        mvc.perform(bulk(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(2));
        assertThat(accountStatus(80002)).isEqualTo("INACTIVE");
        assertThat(accountStatus(80003)).isEqualTo("INACTIVE");
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=80002").update();
        mvc.perform(bulk(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(1));
        assertThat(accountStatus(80002)).isEqualTo("INACTIVE");
        mvc.perform(bulk(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(0));
        mvc.perform(bulk("{\"scholarIds\":[80002,80003],\"status\":\"LEAVE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(2));
        assertThat(accountStatus(80002)).isEqualTo("ACTIVE");
        assertThat(accountStatus(80003)).isEqualTo("ACTIVE");
    }

    @ParameterizedTest @ValueSource(strings={"ENROLLED", "LEAVE", "COMPLETED"})
    void scholarStatusChangeKeepsLockedAccountsLocked(String status) throws Exception {
        jdbc.sql("UPDATE users SET status='LOCKED' WHERE id IN (80002,80003)").update();
        String year=status.equals("COMPLETED") ? ",\"completedYear\":2026" : "";
        mvc.perform(change(80002,"{\"status\":\""+status+"\""+year+"}"))
            .andExpect(status().isOk());
        assertThat(accountStatus(80002)).isEqualTo("LOCKED");
        mvc.perform(bulk("{\"scholarIds\":[80002,80003],\"status\":\""+status+"\""+year+"}"))
            .andExpect(status().isOk());
        assertThat(accountStatus(80002)).isEqualTo("LOCKED");
        assertThat(accountStatus(80003)).isEqualTo("LOCKED");
        mvc.perform(bulk("{\"scholarIds\":[80002,80003],\"status\":\""+status+"\""+year+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(0));
    }

    private String accountStatus(long id) {
        return jdbc.sql("SELECT status FROM users WHERE id=:id").param("id",id).query(String.class).single();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"status\":\"GRADUATED\"}",
        "{\"status\":\"COMPLETED\"}","{\"status\":\"COMPLETED\",\"completedYear\":0}",
        "{\"status\":\"WITHDRAWN\"}","{\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"OTHER\",\"withdrawalReasonDetail\":\"  \"}",
        "{\"status\":\"ENROLLED\",\"completedYear\":2026}",
        "{\"status\":\"LEAVE\",\"withdrawalReason\":\"VOLUNTARY\"}",
        "{\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"VOLUNTARY\",\"withdrawalReasonDetail\":\"숨긴 사유\"}"})
    void rejectsInvalidCombinations(String body) throws Exception {
        mvc.perform(change(80002,body)).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT status FROM scholar_profiles WHERE user_id=80002").query(String.class).single()).isEqualTo("ENROLLED");
    }
    @ParameterizedTest @ValueSource(longs={99999,80001,80004})
    void excludesMissingManagersAndDeleted(long id) throws Exception {
        mvc.perform(change(id,"{\"status\":\"LEAVE\"}")).andExpect(status().isNotFound());
    }
    @Test void bulkDeduplicatesAndCountsActualChanges() throws Exception {
        String body="{\"scholarIds\":[80002,80003,80002],\"status\":\"LEAVE\"}";
        mvc.perform(bulk(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(2));
        mvc.perform(bulk(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success.data.updated").value(0));
    }
    @Test void missingBulkMemberLeavesEveryProfileUntouched() throws Exception {
        mvc.perform(bulk("{\"scholarIds\":[80002,99999],\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"VOLUNTARY\"}"))
            .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT status FROM scholar_profiles WHERE user_id=80002").query(String.class).single()).isEqualTo("ENROLLED");
        assertThat(accountStatus(80002)).isEqualTo("ACTIVE");
    }
    @ParameterizedTest @ValueSource(strings={"[]","[null]","[0]"})
    void rejectsInvalidBulkIds(String ids) throws Exception {
        mvc.perform(bulk("{\"scholarIds\":"+ids+",\"status\":\"LEAVE\"}")).andExpect(status().isBadRequest());
    }
    @Test void requiresManagerForBothRoutes() throws Exception {
        for(String path:new String[]{"/v1/scholars/80002/status","/v1/scholars/status"}) {
            mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
            mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
                .andExpect(status().isForbidden());
        }
    }
}
