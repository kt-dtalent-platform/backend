package kr.co.ktscholarship.backend.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import javax.sql.DataSource;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@SpringBootTest(properties="app.storage.s3.bucket=offline-test-bucket")
@ActiveProfiles("test")
@Import(FilePresignControllerTest.OfflineSigning.class)
class FileDownloadTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    @MockitoSpyBean S3Presigner signer;
    WorkflowFixture fixture; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source);fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup(){fixture.clean();}
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,long id,String role) {
        return request.with(jwt().jwt(j->j.subject(Long.toString(id))).authorities(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    int audits(){return jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE actor_id BETWEEN 70001 AND 70003").query(Integer.class).single();}

    @Test void redirectsOwnUploadedFileWithFiveMinuteSignatureAndAudit() throws Exception {
        fixture.sql("UPDATE submission_files SET upload_status='UPLOADED' WHERE id=73001");
        var response=mvc.perform(as(get("/v1/files/73001/download"),70002,"SCHOLAR")).andExpect(status().isFound())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
        assertThat(response.getResponse().getHeader("Location")).contains("X-Amz-Expires=300","response-content-disposition=");
        assertThat(audits()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE actor_id=70002 AND target_id=73001 AND action='DOWNLOAD_FILE' AND detail IS NULL").query(Integer.class).single()).isOne();
    }
    @Test void managerCanPreviewUploadedFileAcrossCohortsWithoutReturningRawKeyField() throws Exception {
        mvc.perform(as(get("/v1/submissions/72002/files/73002/url"),70001,"MANAGER")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.url").isString()).andExpect(jsonPath("$.success.data.expiresAt").value(org.hamcrest.Matchers.endsWith("Z")))
            .andExpect(jsonPath("$.success.data.s3Key").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(audits()).isOne();
    }
    @Test void rejectsOtherOwnerWrongParentDeletedAndPendingWithoutAudit() throws Exception {
        mvc.perform(as(get("/v1/files/73002/download"),70002,"SCHOLAR")).andExpect(status().isForbidden());
        mvc.perform(as(get("/v1/submissions/72001/files/73002/url"),70001,"MANAGER")).andExpect(status().isNotFound());
        mvc.perform(as(get("/v1/files/73001/download"),70002,"SCHOLAR")).andExpect(status().isNotFound());
        mvc.perform(as(get("/v1/files/73003/download"),70002,"SCHOLAR")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/files/73002/download")).andExpect(status().isUnauthorized());
        assertThat(audits()).isZero();
    }
    @Test void signingFailureDoesNotWriteSuccessfulDownloadAudit() throws Exception {
        doThrow(SdkClientException.create("test-only")).when(signer).presignGetObject(any(GetObjectPresignRequest.class));
        mvc.perform(as(get("/v1/files/73002/download"),70001,"MANAGER")).andExpect(status().isServiceUnavailable());
        assertThat(audits()).isZero();
    }
}
