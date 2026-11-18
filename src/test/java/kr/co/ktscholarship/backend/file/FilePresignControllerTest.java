package kr.co.ktscholarship.backend.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "app.storage.s3.bucket=offline-test-bucket")
@ActiveProfiles("test")
@Import(FilePresignControllerTest.OfflineSigning.class)
class FilePresignControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean S3Presigner presigner;
    MockMvc mvc;

    @TestConfiguration
    static class OfflineSigning {
        @Bean(destroyMethod = "close")
        S3Presigner offlinePresigner() {
            // 외부 통신 없는 서명 검증용. 실제 계정과 무관한 가상 자격 증명이다.
            return S3Presigner.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("offline-test-key", "offline-test-secret"))).build();
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (19001,'업로드 검증')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (19001,19001,'1기')").update();
        jdbc.sql("""
            INSERT INTO users (id,name,email,password_hash,role) VALUES
            (19001,'담당자','upload-manager@example.test','test','MANAGER'),
            (19002,'장학생','upload-scholar@example.test','test','SCHOLAR'),
            (19003,'타인','upload-other@example.test','test','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (19002,19001,'학교'),(19003,19001,'학교')").update();
        jdbc.sql("INSERT INTO request_templates (id,code,name,default_fields) VALUES (19001,'UPLOAD_TEST','파일','[]')").update();
        jdbc.sql("""
            INSERT INTO requests (id,program_id,template_id,title,academic_year,semester,closes_at,status,created_by,published_at)
            VALUES (19001,19001,19001,'업로드',2026,'2','2099-01-01','IN_PROGRESS',19001,'2026-11-01')
            """).update();
        jdbc.sql("INSERT INTO request_targets (request_id,scholar_id) VALUES (19001,19002),(19001,19003)").update();
        jdbc.sql("""
            INSERT INTO request_fields (request_id,field_key,label,field_type,validation) VALUES
            (19001,'receipt','파일','FILE','{"allowedExtensions":["pdf","jpg"],"maxSizeMb":1,"maxFiles":2}' FORMAT JSON),
            (19001,'other_file','보완 파일','FILE','{"allowedExtensions":["pdf"],"maxSizeMb":1,"maxFiles":2}' FORMAT JSON),
            (19001,'text','이름','TEXT',NULL)
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id,request_id,scholar_id,status,answers) VALUES
            (19001,19001,19002,'DRAFT','{}'),(19002,19001,19003,'DRAFT','{}')
            """).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM submission_files WHERE submission_id IN (19001,19002)").update();
        jdbc.sql("DELETE FROM revision_request_fields WHERE revision_request_id IN (19001,19002)").update();
        jdbc.sql("DELETE FROM revision_requests WHERE submission_id IN (19001,19002)").update();
        jdbc.sql("DELETE FROM submissions WHERE request_id=19001").update();
        jdbc.sql("DELETE FROM request_fields WHERE request_id=19001").update();
        jdbc.sql("DELETE FROM request_targets WHERE request_id=19001").update();
        jdbc.sql("DELETE FROM requests WHERE id=19001").update();
        jdbc.sql("DELETE FROM request_templates WHERE id=19001").update();
        jdbc.sql("DELETE FROM scholar_profiles WHERE cohort_id=19001").update();
        jdbc.sql("DELETE FROM users WHERE id BETWEEN 19001 AND 19003").update();
        jdbc.sql("DELETE FROM cohorts WHERE id=19001").update();
        jdbc.sql("DELETE FROM programs WHERE id=19001").update();
    }

    @Test
    void createsPendingAndOfflineSignedPutUrlForTenMinutes() throws Exception {
        Instant before = Instant.now();
        var result = upload("receipt", "성적.PDF", 1048576).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success.data.fileId").isNumber())
            .andExpect(jsonPath("$.success.data.s3Key").doesNotExist()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).at("/success/data");
        String url = data.get("uploadUrl").asText();
        assertThat(url).contains("offline-test-bucket.s3.ap-northeast-2.amazonaws.com", "X-Amz-Expires=600", "X-Amz-Signature=")
            .doesNotContain("성적");
        assertThat(Instant.parse(data.get("expiresAt").asText())).isBetween(before.plusSeconds(598), Instant.now().plusSeconds(601));
        var stored = jdbc.sql("SELECT * FROM submission_files WHERE id=:id").param("id",data.get("fileId").asLong()).query().singleRow();
        assertThat(stored).containsEntry("upload_status","PENDING").containsEntry("original_name","성적.PDF");
        assertThat(stored.get("s3_key").toString()).startsWith("submissions/19001/").endsWith(".pdf");
        var createdAt = jdbc.sql("SELECT created_at FROM submission_files WHERE id=:id")
            .param("id",data.get("fileId").asLong()).query(LocalDateTime.class).single();
        assertThat(createdAt.toInstant(ZoneOffset.UTC)).isBetween(before.minusSeconds(1),Instant.now());
    }

    @Test
    void checksOwnerRoleAndAuthentication() throws Exception {
        mvc.perform(post("/v1/submissions/19002/files/presign").contentType(MediaType.APPLICATION_JSON).content(body("receipt","a.pdf",1))
            .with(jwt().jwt(j->j.subject("19002")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mvc.perform(post("/v1/submissions/19001/files/presign").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/submissions/19001/files/presign").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(j->j.subject("19001")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void validatesExtensionSizeAndFileFieldBeforeInsert() throws Exception {
        upload("receipt","a.exe",1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_EXTENSION"));
        upload("receipt","a.pdf",1048577).andExpect(status().is(413)).andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        upload("text","a.pdf",1).andExpect(status().isBadRequest());
        upload("missing","a.pdf",1).andExpect(status().isBadRequest());
        upload("receipt","../a.pdf",1).andExpect(status().isBadRequest());
        assertThat(fileCount()).isZero();
    }

    @Test
    void pendingAndUploadedCountButDeletedAndOtherSubmissionDoNot() throws Exception {
        upload("receipt","a.pdf",1).andExpect(status().isOk());
        jdbc.sql("UPDATE submission_files SET upload_status='DELETED' WHERE submission_id=19001").update();
        upload("receipt","b.pdf",1).andExpect(status().isOk());
        jdbc.sql("UPDATE submission_files SET upload_status='UPLOADED' WHERE submission_id=19001 AND upload_status='PENDING'").update();
        upload("receipt","c.pdf",1).andExpect(status().isOk());
        upload("receipt","d.pdf",1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FILE_COUNT_EXCEEDED"));
        assertThat(repositoryCountActive()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings={"SUBMITTED","RESUBMITTED","CONFIRMED"})
    void locksCompletedSubmissions(String state) throws Exception {
        jdbc.sql("UPDATE submissions SET status=:state WHERE id=19001").param("state",state).update();
        upload("receipt","a.pdf",1).andExpect(status().is(422));
        assertThat(fileCount()).isZero();
    }

    @Test
    void checksDeadlinePublicationAndCurrentTarget() throws Exception {
        jdbc.sql("UPDATE requests SET closes_at='2020-01-01' WHERE id=19001").update();
        upload("receipt","a.pdf",1).andExpect(status().is(422));
        jdbc.sql("UPDATE requests SET closes_at='2099-01-01',published_at=NULL WHERE id=19001").update();
        upload("receipt","a.pdf",1).andExpect(status().is(422));
        jdbc.sql("UPDATE requests SET published_at='2026-11-01',status='CLOSED' WHERE id=19001").update();
        upload("receipt","a.pdf",1).andExpect(status().is(422));
        jdbc.sql("UPDATE requests SET status='IN_PROGRESS' WHERE id=19001").update();
        jdbc.sql("DELETE FROM request_targets WHERE request_id=19001 AND scholar_id=19002").update();
        upload("receipt","a.pdf",1).andExpect(status().isForbidden());
    }

    @Test
    void revisionAllowsOnlyLatestUnresolvedFields() throws Exception {
        jdbc.sql("UPDATE submissions SET status='REVISION_REQUIRED' WHERE id=19001").update();
        jdbc.sql("""
            INSERT INTO revision_requests (id,submission_id,reason,requested_by,requested_at) VALUES
            (19001,19001,'과거',19001,'2026-11-01'),(19002,19001,'최신',19001,'2026-11-02')
            """).update();
        jdbc.sql("INSERT INTO revision_request_fields (revision_request_id,field_key) VALUES (19001,'receipt'),(19002,'other_file')").update();
        upload("receipt","a.pdf",1).andExpect(status().is(422));
        upload("other_file","a.pdf",1).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM submissions WHERE id=19001").query(String.class).single()).isEqualTo("REVISION_REQUIRED");
    }

    @Test
    void signingFailureRollsBackPendingInsert() throws Exception {
        doThrow(SdkClientException.create("test-only failure")).when(presigner).presignPutObject(any(PutObjectPresignRequest.class));
        upload("receipt","a.pdf",1).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("STORAGE_UNAVAILABLE"));
        assertThat(fileCount()).isZero();
    }

    @Test
    void missingLimitsDoNotAllowUnlimitedUpload() throws Exception {
        jdbc.sql("UPDATE request_fields SET validation=NULL WHERE request_id=19001 AND field_key='receipt'").update();
        upload("receipt","a.pdf",1).andExpect(status().isBadRequest());
        assertThat(fileCount()).isZero();
    }

    @Test
    void rejectsMissingSubmissionAndMalformedInput() throws Exception {
        var scholar = jwt().jwt(j->j.subject("19002")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"));
        mvc.perform(post("/v1/submissions/19999/files/presign").contentType(MediaType.APPLICATION_JSON)
            .content(body("receipt","a.pdf",1)).with(scholar))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_FOUND"));
        for (String id : new String[]{"0","-1","abc"}) {
            mvc.perform(post("/v1/submissions/"+id+"/files/presign").contentType(MediaType.APPLICATION_JSON)
                .content(body("receipt","a.pdf",1)).with(scholar)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/v1/submissions/19001/files/presign").contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldKey\":\"receipt\",\"fileName\":\"a.pdf\",\"contentType\":\"application/pdf\",\"size\":1.5}").with(scholar))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        for (String mime : new String[]{"invalid", "*/*", "application/pdf\r\nX-Test: value"}) {
            mvc.perform(post("/v1/submissions/19001/files/presign").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fieldKey","receipt","fileName","a.pdf","contentType",mime,"size",1)))
                .with(scholar)).andExpect(status().isBadRequest());
        }
    }

    private ResultActions upload(String field,String name,long size) throws Exception {
        return mvc.perform(post("/v1/submissions/19001/files/presign").contentType(MediaType.APPLICATION_JSON).content(body(field,name,size))
            .with(jwt().jwt(j->j.subject("19002")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))));
    }
    private String body(String field,String name,long size) {
        return json.writeValueAsString(Map.of("fieldKey",field,"fileName",name,"contentType","application/pdf","size",size));
    }
    private int fileCount() {
        return jdbc.sql("SELECT COUNT(*) FROM submission_files WHERE submission_id=19001").query(Integer.class).single();
    }
    private long repositoryCountActive() {
        return jdbc.sql("SELECT COUNT(*) FROM submission_files WHERE submission_id=19001 AND upload_status IN ('PENDING','UPLOADED')")
            .query(Long.class).single();
    }
}
