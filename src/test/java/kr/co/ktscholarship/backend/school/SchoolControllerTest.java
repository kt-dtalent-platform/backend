package kr.co.ktscholarship.backend.school;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class SchoolControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void seedsExactlyProvidedActiveSchoolsAndSeparatesCampuses() throws Exception {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools").query(Integer.class).single()).isEqualTo(455);
        mvc.perform(get("/v1/schools").param("keyword","고려대학교")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items.length()").value(2))
            .andExpect(jsonPath("$.success.data.items[0].name").value("고려대학교"))
            .andExpect(jsonPath("$.success.data.items[1].name").value("고려대학교 (세종캠퍼스)"))
            .andExpect(jsonPath("$.success.data.items[1].region").value("세종"));
    }
    @ParameterizedTest @ValueSource(strings={"고려 대학교 (세종 캠퍼스)","고려대학교(세종캠퍼스)"})
    void ignoresSpacesAndParentheses(String keyword) throws Exception {
        mvc.perform(get("/v1/schools").param("keyword",keyword)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items.length()").value(1));
    }
    @ParameterizedTest @ValueSource(strings={"","  ","%","_","목록에없는테스트학교"})
    void returnsEmptyForBlankUnknownOrLiteralWildcards(String keyword) throws Exception {
        mvc.perform(get("/v1/schools").param("keyword",keyword)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items").isEmpty());
    }
    @Test void limitsResultsAndRejectsInvalidSizes() throws Exception {
        mvc.perform(get("/v1/schools?keyword=대학교&size=1")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.items.length()").value(1));
        for(String size:new String[]{"0","51","abc"}) mvc.perform(get("/v1/schools").param("size",size)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest());
    }
    @Test void requiresManager() throws Exception {
        mvc.perform(get("/v1/schools?keyword=고려")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/schools?keyword=고려").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
    }
    @Test
    @org.springframework.transaction.annotation.Transactional
    void allowsUnlistedSchoolNamesOnRegistrationAndUpdate() throws Exception {
        jdbc.sql("INSERT INTO programs(id,name) VALUES(83001,'직접 입력 검증')").update();
        jdbc.sql("INSERT INTO cohorts(id,program_id,name) VALUES(83001,83001,'2026')").update();
        mvc.perform(post("/v1/scholars").contentType("application/json").content("""
            {"cohortId":83001,"name":"검증 학생","email":"unlisted-school@example.test","university":"목록에없는학교 (신설캠퍼스)"}
            """).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isCreated());
        long id=jdbc.sql("SELECT id FROM users WHERE email='unlisted-school@example.test'").query(Long.class).single();
        mvc.perform(patch("/v1/scholars/{id}",id).contentType("application/json")
            .content("{\"university\":\"직접입력학교 (새캠퍼스)\"}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT school_name FROM scholar_profiles WHERE user_id=:id").param("id",id).query(String.class).single())
            .isEqualTo("직접입력학교 (새캠퍼스)");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schools").query(Integer.class).single()).isEqualTo(455);
    }
}
