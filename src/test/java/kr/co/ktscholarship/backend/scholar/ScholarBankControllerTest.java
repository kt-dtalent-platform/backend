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
class ScholarBankControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    private static final String BODY="{\"bankName\":\"검증은행\",\"accountNumber\":\"000-1234-5678\",\"accountHolder\":\"검증 학생\"}";
    @BeforeEach void seed() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs(id,name) VALUES(84001,'계좌 검증')").update();
        jdbc.sql("INSERT INTO cohorts(id,program_id,name) VALUES(84001,84001,'2026')").update();
        jdbc.sql("""
            INSERT INTO users(id,name,email,password_hash,role,deleted_at) VALUES
            (84001,'담당자','bank-manager@example.test','test','MANAGER',NULL),
            (84002,'학생A','bank-a@example.test','test','SCHOLAR',NULL),
            (84003,'학생B','bank-b@example.test','test','SCHOLAR',NULL),
            (84004,'탈퇴','bank-deleted@example.test','test','SCHOLAR','2026-01-01')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles(user_id,cohort_id,school_name) VALUES(84002,84001,'학교A'),(84003,84001,'학교B'),(84004,84001,'학교C')").update();
    }
    MockHttpServletRequestBuilder scholar(MockHttpServletRequestBuilder request,long id) {
        return request.with(jwt().jwt(j->j.subject(Long.toString(id))).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")));
    }
    @Test void studentWritesOwnAccountAndOnlyManagerSeesFullNumber() throws Exception {
        var updated=mvc.perform(scholar(patch("/v1/me/bank-account").contentType("application/json").content(BODY),84002))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.success.data.updatedAt").value(org.hamcrest.Matchers.endsWith("Z")))
            .andReturn().getResponse().getContentAsString();
        assertThat(updated).doesNotContain("00012345678","accountNumber");
        var own=mvc.perform(scholar(get("/v1/me/bank-account"),84002)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.maskedAccountNumber").value("****5678"))
            .andExpect(jsonPath("$.success.data.accountNumber").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(own).doesNotContain("00012345678");
        mvc.perform(get("/v1/scholars/84002").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.success.data.bankAccount.accountNumber").value("00012345678"));
        assertThat(jdbc.sql("SELECT bank_account_number FROM scholar_profiles WHERE user_id=84003").query().singleRow())
            .containsEntry("bank_account_number",null);
        assertThat(jdbc.sql("SELECT action FROM audit_logs WHERE actor_id=84002").query(String.class).single()).isEqualTo("UPDATE_BANK_ACCOUNT");
    }
    @Test void missingAccountIsNullAndSelfIdCannotBeOverridden() throws Exception {
        mvc.perform(scholar(get("/v1/me/bank-account?userId=84002"),84003)).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test void rejectsUnauthorizedAndManagerWritesAndScholarFullRead() throws Exception {
        mvc.perform(patch("/v1/me/bank-account").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/v1/me/bank-account").contentType("application/json").content(BODY)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")))).andExpect(status().isForbidden());
        mvc.perform(scholar(get("/v1/scholars/84002"),84002)).andExpect(status().isForbidden());
        mvc.perform(scholar(patch("/v1/me/bank-account").contentType("application/json").content(BODY),84004))
            .andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"bankName\":\"은행\",\"accountNumber\":\"-----\",\"accountHolder\":\"학생\"}",
        "{\"bankName\":\"은행\",\"accountNumber\":\"1234\",\"accountHolder\":\"학생\"}",
        "{\"bankName\":\"은행\",\"accountNumber\":\"12345abc\",\"accountHolder\":\"학생\"}",
        "{\"bankName\":\"은행\",\"accountNumber\":\"12345678\",\"accountHolder\":\" \"}"})
    void rejectsInvalidInputWithoutSaving(String body) throws Exception {
        mvc.perform(scholar(patch("/v1/me/bank-account").contentType("application/json").content(body),84002))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT bank_account_number FROM scholar_profiles WHERE user_id=84002").query().singleRow())
            .containsEntry("bank_account_number",null);
    }
    @Test void doesNotPrintSensitiveRecordValues() {
        assertThat(new ScholarBankAccount("은행","12345678","학생").toString()).doesNotContain("12345678","학생");
    }
}
