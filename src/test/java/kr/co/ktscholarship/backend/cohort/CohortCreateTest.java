package kr.co.ktscholarship.backend.cohort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class CohortCreateTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (9611,'기수 생성 검증'),(9612,'다른 프로그램')").update();
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM cohorts WHERE program_id IN (9611,9612)").update();
        jdbc.sql("DELETE FROM programs WHERE id IN (9611,9612)").update();
    }
    @Test void createsAndImmediatelyListsCohortForRegistration() throws Exception {
        mvc.perform(create(9611,"{\"name\":\" 2027년 선발 \"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.success.data.id").isNumber())
            .andExpect(jsonPath("$.success.data.name").value("2027년 선발"))
            .andExpect(jsonPath("$.success.data.scholarCount").value(0));
        mvc.perform(get("/v1/programs/9611/cohorts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items[0].name").value("2027년 선발"));
    }
    @Test void preventsDuplicateWithinProgramButAllowsAnotherProgram() throws Exception {
        String body="{\"name\":\"2027년 선발\"}";
        mvc.perform(create(9611,body)).andExpect(status().isCreated());
        mvc.perform(create(9611,body)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DUPLICATE_COHORT"));
        mvc.perform(create(9612,body)).andExpect(status().isCreated());
    }
    @Test void validatesDateOrderAndPersistsOptionalDates() throws Exception {
        mvc.perform(create(9611,"{\"name\":\"날짜\",\"startedOn\":\"2027-03-01\",\"endedOn\":\"2027-02-01\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0]").value("endedOn"));
        mvc.perform(create(9611,"{\"name\":\"날짜\",\"startedOn\":\"2027-03-01\",\"endedOn\":\"2028-02-01\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.success.data.startedOn").value("2027-03-01"))
            .andExpect(jsonPath("$.success.data.endedOn").value("2028-02-01"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"name\":null}","{\"name\":\"   \"}"})
    void rejectsEmptyName(String body) throws Exception {
        mvc.perform(create(9611,body)).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cohorts WHERE program_id=9611").query(Long.class).single()).isZero();
    }
    @Test void validatesNameLengthAfterStrippingWhitespace() throws Exception {
        String name = "a".repeat(50);
        mvc.perform(create(9611,"{\"name\":\"  " + name + "  \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success.data.name").value(name));
        mvc.perform(create(9611,"{\"name\":\"  " + "a".repeat(51) + "  \"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cohorts WHERE program_id=9611").query(Long.class).single()).isEqualTo(1);
    }
    @Test void rejectsLongNameAndMissingProgram() throws Exception {
        mvc.perform(create(9611,"{\"name\":\""+"a".repeat(51)+"\"}")).andExpect(status().isBadRequest());
        mvc.perform(create(999999,"{\"name\":\"기수\"}")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROGRAM_NOT_FOUND"));
    }
    @Test void rejectsAnonymousAndScholar() throws Exception {
        mvc.perform(post("/v1/programs/9611/cohorts").contentType("application/json").content("{\"name\":\"기수\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/programs/9611/cohorts").contentType("application/json").content("{\"name\":\"기수\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(long program,String body) {
        return post("/v1/programs/"+program+"/cohorts").contentType("application/json").content(body)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
}
