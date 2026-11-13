package kr.co.ktscholarship.backend.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class FileLifecycleTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    @Autowired FileLifecycleService service;
    @MockitoBean FileObjectStore storage;
    WorkflowFixture fixture;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source); fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(storage.head("workflow/receipt")).thenReturn(new FileObjectStore.Metadata(123,"application/pdf"));
    }
    @AfterEach void cleanup() { fixture.clean(); }
    MockHttpServletRequestBuilder scholar(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j->j.subject("70002")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")));
    }
    String uploadStatus(long id) { return jdbc.sql("SELECT upload_status FROM submission_files WHERE id=:id").param("id",id).query(String.class).single(); }

    @Test void completesOnceAndReturnsServerSize() throws Exception {
        for(int i=0;i<2;i++) mvc.perform(scholar(post("/v1/submissions/72001/files/73001/complete")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.sizeBytes").value(123))
            .andExpect(jsonPath("$.success.data.uploadStatus").value("UPLOADED"));
        assertThat(uploadStatus(73001)).isEqualTo("UPLOADED");
        verify(storage,times(1)).head("workflow/receipt");
    }
    @Test void mismatchDeletesObjectAndCommitsDeletedMarkerEvenOn400() throws Exception {
        when(storage.head("workflow/receipt")).thenReturn(new FileObjectStore.Metadata(124,"application/pdf"));
        mvc.perform(scholar(post("/v1/submissions/72001/files/73001/complete"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("FILE_MISMATCH"));
        verify(storage).delete("workflow/receipt");
        assertThat(uploadStatus(73001)).isEqualTo("DELETED");
    }
    @Test void wrongContentTypeIsRejected() throws Exception {
        when(storage.head("workflow/receipt")).thenReturn(new FileObjectStore.Metadata(123,"text/html"));
        mvc.perform(scholar(post("/v1/submissions/72001/files/73001/complete"))).andExpect(status().isBadRequest());
        assertThat(uploadStatus(73001)).isEqualTo("DELETED");
    }
    @Test void missingObjectAndStorageFailureDoNotConfirmOrDeleteDatabaseRow() throws Exception {
        when(storage.head("workflow/receipt")).thenThrow(new BusinessException(ErrorCode.UPLOAD_NOT_FOUND));
        mvc.perform(scholar(post("/v1/submissions/72001/files/73001/complete"))).andExpect(status().isBadRequest());
        assertThat(uploadStatus(73001)).isEqualTo("PENDING");
        doThrow(new BusinessException(ErrorCode.STORAGE_UNAVAILABLE)).when(storage).delete("workflow/receipt");
        mvc.perform(scholar(delete("/v1/files/73001"))).andExpect(status().isServiceUnavailable());
        assertThat(uploadStatus(73001)).isEqualTo("PENDING");
    }
    @Test void checksOwnerParentRoleAndAuthentication() throws Exception {
        mvc.perform(scholar(post("/v1/submissions/72002/files/73002/complete"))).andExpect(status().isForbidden());
        mvc.perform(scholar(post("/v1/submissions/72001/files/73002/complete"))).andExpect(status().isNotFound());
        mvc.perform(scholar(delete("/v1/files/73002"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/submissions/72001/files/73001/complete")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/files/73001").with(jwt().jwt(j->j.subject("70001"))
            .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")))).andExpect(status().isForbidden());
        verify(storage,never()).head("workflow/other");
    }
    @Test void deletesPendingAndAlreadyDeletedIsIdempotent() throws Exception {
        for(int i=0;i<2;i++) mvc.perform(scholar(delete("/v1/files/73001"))).andExpect(status().isOk());
        assertThat(uploadStatus(73001)).isEqualTo("DELETED");
        verify(storage,times(1)).delete("workflow/receipt");
    }
    @ParameterizedTest @ValueSource(strings={"SUBMITTED","RESUBMITTED","CONFIRMED"})
    void locksCompletedStates(String state) throws Exception {
        jdbc.sql("UPDATE submissions SET status=:state WHERE id=72001").param("state",state).update();
        mvc.perform(scholar(delete("/v1/files/73001"))).andExpect(status().is(422));
        mvc.perform(scholar(post("/v1/submissions/72001/files/73001/complete"))).andExpect(status().is(422));
        verifyNoInteractions(storage);
    }
    @Test void latestRevisionAndDeadlineAreEnforced() throws Exception {
        fixture.sql("UPDATE submissions SET status='REVISION_REQUIRED' WHERE id=72001");
        fixture.sql("INSERT INTO revision_requests(id,submission_id,reason,requested_by,requested_at) VALUES(74001,72001,'이전',70001,'2026-01-01'),(74002,72001,'최신',70001,'2026-02-01')");
        fixture.sql("INSERT INTO revision_request_fields(revision_request_id,field_key) VALUES(74001,'receipt'),(74002,'other_file')");
        mvc.perform(scholar(delete("/v1/files/73001"))).andExpect(status().is(422));
        fixture.sql("UPDATE revision_request_fields SET field_key='receipt' WHERE revision_request_id=74002");
        fixture.sql("UPDATE requests SET closes_at='2020-01-01' WHERE id=71001");
        mvc.perform(scholar(delete("/v1/files/73001"))).andExpect(status().is(422));
    }
    @Test void cleanupOnlyRemovesPendingOlderThan24Hours() {
        jdbc.sql("UPDATE submission_files SET created_at=:time WHERE id IN (73001,73002)")
            .param("time",UtcDateTimes.toDb(Instant.now().minus(25,ChronoUnit.HOURS))).update();
        service.cleanPending(73001); service.cleanPending(73002);
        assertThat(uploadStatus(73001)).isEqualTo("DELETED");
        assertThat(uploadStatus(73002)).isEqualTo("UPLOADED");
        verify(storage).delete("workflow/receipt");
        verify(storage,never()).delete("workflow/other");
    }
}
