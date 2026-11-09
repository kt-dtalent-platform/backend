package kr.co.ktscholarship.backend.cohort;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class CohortControllerTest {

    @MockitoBean
    private CohortRepository repository;

    @Autowired
    private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsCohortsInTheCommonResponse() throws Exception {
        when(repository.existsProgram(1)).thenReturn(true);
        when(repository.findByProgramId(1)).thenReturn(List.of(
                new CohortItem(6, "6기", LocalDate.of(2026, 3, 1), null, 47)
        ));

        mockMvc.perform(get("/v1/programs/1/cohorts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success.data.items.length()").value(1))
                .andExpect(jsonPath("$.success.data.items[0].id").value(6))
                .andExpect(jsonPath("$.success.data.items[0].name").value("6기"))
                .andExpect(jsonPath("$.success.data.items[0].startedOn").value("2026-03-01"))
                .andExpect(jsonPath("$.success.data.items[0].endedOn").value(nullValue()))
                .andExpect(jsonPath("$.success.data.items[0].scholarCount").value(47))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.meta.path").value("/v1/programs/1/cohorts"))
                .andExpect(jsonPath("$.meta.timestamp").isNotEmpty());
    }

    @Test
    void rejectsMissingProgramBeforeQueryingCohorts() throws Exception {
        mockMvc.perform(get("/v1/programs/999/cohorts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.success").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("PROGRAM_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("프로그램을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.meta.path").value("/v1/programs/999/cohorts"));

        verify(repository).existsProgram(999);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnauthenticatedRequestWithCommon401() throws Exception {
        mockMvc.perform(get("/v1/programs/1/cohorts"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/programs/1/cohorts"));
    }

    @Test
    void rejectsScholarRequestWithCommon403() throws Exception {
        mockMvc.perform(get("/v1/programs/1/cohorts")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.resultType").value("FAIL"))
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.meta.path").value("/v1/programs/1/cohorts"));
    }
}
