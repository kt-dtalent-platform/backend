package kr.co.ktscholarship.backend.file;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.BackendApplication;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.file.FileObjectStore;
import kr.co.ktscholarship.backend.submission.TargetDeadlineService;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(classes=BackendApplication.class,properties={"app.storage.s3.enabled=false","app.storage.s3.bucket=offline-test-bucket"})
@ActiveProfiles("test")
@Transactional
@Import(PersonalDeadlineFilesTest.OfflineSigner.class)
class PersonalDeadlineFilesTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;
    @Autowired TargetDeadlineService deadlines;
    @MockitoBean FileObjectStore storage;
    MockMvc mvc;
    @TestConfiguration static class OfflineSigner {
        @Bean(destroyMethod="close") S3Presigner signer() {
            return S3Presigner.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key","test-secret"))).build();
        }
    }
    @BeforeEach void seed() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        new WorkflowFixture(jdbc,source).seed();
        jdbc.sql("UPDATE requests SET closes_at=:past WHERE id=71001")
            .param("past",UtcDateTimes.toDb(Instant.now().minus(1,ChronoUnit.DAYS))).update();
        jdbc.sql("UPDATE submission_files SET upload_status='PENDING' WHERE id=73002").update();
        deadlines.extend(71001,70002,Instant.now().plus(1,ChronoUnit.DAYS),70001);
        when(storage.head(anyString())).thenReturn(new FileObjectStore.Metadata(123,"application/pdf"));
    }
    MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder request,long id) {
        return request.with(jwt().jwt(j->j.subject(Long.toString(id))).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")));
    }
    MockHttpServletRequestBuilder presign(long submission,long id) {
        return user(post("/v1/submissions/{s}/files/presign",submission).contentType("application/json")
            .content("{\"fieldKey\":\"receipt\",\"fileName\":\"검증.pdf\",\"contentType\":\"application/pdf\",\"size\":123}"),id);
    }
    @Test void fileMutationsRemainAvailableToBothExtendedAndLateStudents() throws Exception {
        mvc.perform(presign(72001,70002)).andExpect(status().isOk());
        mvc.perform(user(post("/v1/submissions/72001/files/73001/complete"),70002)).andExpect(status().isOk());
        mvc.perform(user(delete("/v1/files/73001"),70002)).andExpect(status().isOk());
        mvc.perform(presign(72002,70003)).andExpect(status().isOk());
        mvc.perform(user(post("/v1/submissions/72002/files/73002/complete"),70003)).andExpect(status().isOk());
        mvc.perform(user(delete("/v1/files/73002"),70003)).andExpect(status().isOk());
        verify(storage).head("workflow/other");
        verify(storage).delete("workflow/other");
    }
    @Test void closedRequestBlocksAllFileMutationsEvenWithAnExtension() throws Exception {
        jdbc.sql("UPDATE requests SET status='CLOSED' WHERE id=71001").update();
        mvc.perform(presign(72001,70002)).andExpect(status().isUnprocessableEntity());
        mvc.perform(user(post("/v1/submissions/72001/files/73001/complete"),70002)).andExpect(status().isUnprocessableEntity());
        mvc.perform(user(delete("/v1/files/73001"),70002)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(storage);
    }
    @Test void closedRequestAllowsOnlyRequestedRevisionFileEvenAfterDueDate() throws Exception {
        jdbc.sql("UPDATE requests SET status='CLOSED' WHERE id=71001").update();
        jdbc.sql("UPDATE submissions SET status='REVISION_REQUIRED' WHERE id=72001").update();
        jdbc.sql("UPDATE request_targets SET extended_due_at='2020-01-01' WHERE request_id=71001 AND scholar_id=70002").update();
        jdbc.sql("INSERT INTO revision_requests(id,submission_id,reason,requested_by) VALUES(74001,72001,'파일 보완',70001)").update();
        jdbc.sql("INSERT INTO revision_request_fields(revision_request_id,field_key) VALUES(74001,'receipt')").update();
        mvc.perform(presign(72001,70002)).andExpect(status().isOk());
        mvc.perform(user(post("/v1/submissions/72001/files/73001/complete"),70002)).andExpect(status().isOk());
        mvc.perform(user(delete("/v1/files/73001"),70002)).andExpect(status().isOk());
        mvc.perform(presign(72002,70003)).andExpect(status().isUnprocessableEntity());
        mvc.perform(user(post("/v1/submissions/72001/files/presign").contentType("application/json")
            .content("{\"fieldKey\":\"other_file\",\"fileName\":\"test.pdf\",\"contentType\":\"application/pdf\",\"size\":123}"),70002))
            .andExpect(status().isUnprocessableEntity());
        jdbc.sql("UPDATE revision_requests SET resolved_at=CURRENT_TIMESTAMP WHERE id=74001").update();
        mvc.perform(presign(72001,70002)).andExpect(status().isUnprocessableEntity());
        verify(storage).head("workflow/receipt");
        verify(storage).delete("workflow/receipt");
    }

}
