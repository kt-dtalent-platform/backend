package kr.co.ktscholarship.backend.requesttemplate;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RequestTemplateControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsStoredTemplatesWithCommonResponse() throws Exception {
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields) VALUES
                (103, 'TRANSPORT_CLAIM', '교통비 증빙', '[]' FORMAT JSON),
                (115, 'CUSTOM', '사용자 정의', '[]' FORMAT JSON)
            """).update();

        mockMvc.perform(get("/v1/request-templates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.items", hasSize(2)))
            .andExpect(jsonPath("$.success.data.items[*].id", containsInAnyOrder(103, 115)))
            .andExpect(jsonPath("$.success.data.items[*].code", containsInAnyOrder("TRANSPORT_CLAIM", "CUSTOM")))
            .andExpect(jsonPath("$.success.data.items[*].name", containsInAnyOrder("교통비 증빙", "사용자 정의")))
            .andExpect(jsonPath("$.success.data.items[0].fields").isArray())
            .andExpect(jsonPath("$.success.data.items[1].fields").isEmpty())
            .andExpect(jsonPath("$.error").value(nullValue()))
            .andExpect(jsonPath("$.meta.path").value("/v1/request-templates"));
    }

    @Test
    void preservesFieldDefinitionsAndJsonTypes() throws Exception {
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (103, 'TRANSPORT_CLAIM', '교통비 증빙', :fields FORMAT JSON)
            """).param("fields", """
            [{"fieldKey":"amount","label":"금액","fieldType":"NUMBER","required":true,
            "validation":{"min":0,"max":300000}},
            {"fieldKey":"receipt","label":"영수증","fieldType":"FILE","required":true,
            "validation":{"allowedExtensions":["jpg","png","pdf"],"maxSizeMb":10}}]
            """).update();

        mockMvc.perform(get("/v1/request-templates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items[0].fields", hasSize(2)))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].fieldKey").value("amount"))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].label").value("금액"))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].fieldType").value("NUMBER"))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].required").value(true))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].validation.min").value(0))
            .andExpect(jsonPath("$.success.data.items[0].fields[0].validation.max").value(300000))
            .andExpect(jsonPath("$.success.data.items[0].fields[1].fieldKey").value("receipt"))
            .andExpect(jsonPath("$.success.data.items[0].fields[1].validation.allowedExtensions[2]").value("pdf"));
    }

    @Test
    void returnsEmptyArrayWhenNoTemplatesAreStored() throws Exception {
        mockMvc.perform(get("/v1/request-templates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.items").isArray())
            .andExpect(jsonPath("$.success.data.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}"})
    void rejectsStoredFieldsThatAreNotAnArray(String fields) throws Exception {
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (103, 'TEST_FIELDS', '검증용', :fields FORMAT JSON)
            """).param("fields", fields).update();

        mockMvc.perform(get("/v1/request-templates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void rejectsUnauthenticatedRequestWithCommon401() throws Exception {
        mockMvc.perform(get("/v1/request-templates"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/request-templates"));
    }

    @Test
    void rejectsScholarRequestWithCommon403() throws Exception {
        mockMvc.perform(get("/v1/request-templates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/request-templates"));
    }
}
