package kr.co.ktscholarship.backend.submission;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionFormControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    private MockMvc mvc;

    private RequestPostProcessor scholar(long id) {
        return jwt().jwt(j -> j.subject(Long.toString(id)))
            .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (91,'검증 프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (96,91,'6기')").update();
        jdbc.sql("""
            INSERT INTO users (id,email,password_hash,name,role) VALUES
                (910,'manager@form.test','test-only','담당자','MANAGER'),
                (911,'scholar@form.test','test-only','장학생','SCHOLAR'),
                (912,'other@form.test','test-only','다른 장학생','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (911,96,'학교'),(912,96,'학교')").update();
        jdbc.sql("INSERT INTO request_templates (id,code,name,default_fields) VALUES (91,'FORM_HTTP_TEST','검증','[]' FORMAT JSON)").update();
        jdbc.sql("""
            INSERT INTO requests (id,program_id,template_id,title,academic_year,semester,closes_at,status,created_by,published_at)
            VALUES (931,91,91,'공개 폼',2026,'2','2026-12-01','IN_PROGRESS',910,'2026-11-01'),
                (932,91,91,'공개 전',2026,'2','2026-12-01','DRAFT',910,NULL),
                (933,91,91,'다른 학생 폼',2026,'2','2026-12-01','IN_PROGRESS',910,'2026-11-01')
            """).update();
        jdbc.sql("INSERT INTO request_targets (id,request_id,scholar_id) VALUES (941,931,911),(942,931,912),(943,933,912)").update();
        jdbc.sql("""
            INSERT INTO submissions (id,request_id,scholar_id,status,answers,version) VALUES
                (951,931,911,'DRAFT','{"value":"본인 입력"}' FORMAT JSON,1),
                (952,931,912,'DRAFT','{"value":"다른 학생 입력"}' FORMAT JSON,2)
            """).update();
    }

    @Test
    void returnsOnlyTokenOwnersSubmissionEvenWithAnotherScholarId() throws Exception {
        mvc.perform(get("/v1/me/submissions/931?scholarId=912&userId=912").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.request.requestDueAt").value("2026-12-01T00:00:00Z"))
            .andExpect(jsonPath("$.resultType").value("SUCCESS"))
            .andExpect(jsonPath("$.success.data.request.id").value(931))
            .andExpect(jsonPath("$.success.data.submission.id").value(951))
            .andExpect(jsonPath("$.success.data.submission.answers.value").value("본인 입력"))
            .andExpect(jsonPath("$.success.data.submission.version").value(1))
            .andExpect(jsonPath("$.meta.path").value("/v1/me/submissions/931"));
        mvc.perform(get("/v1/me/submissions/931?scholarId=911").with(scholar(912)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.submission.id").value(952))
            .andExpect(jsonPath("$.success.data.submission.answers.value").value("다른 학생 입력"));
    }

    @Test
    void isolatesFilesAndRevisionDetailsByTokenOwner() throws Exception {
        jdbc.sql("UPDATE submissions SET status = 'REVISION_REQUIRED' WHERE id IN (951,952)").update();
        jdbc.sql("""
            INSERT INTO submission_files
                (id,submission_id,field_key,s3_key,original_name,content_type,size_bytes,upload_status)
            VALUES (961,951,'own_file','private/own','own.pdf','application/pdf',100,'UPLOADED'),
                (962,952,'other_file','private/other','other.pdf','application/pdf',100,'UPLOADED')
            """).update();
        jdbc.sql("""
            INSERT INTO revision_requests (id,submission_id,reason,requested_by)
            VALUES (971,951,'본인 보완 사유',910),(972,952,'다른 학생 보완 사유',910)
            """).update();
        jdbc.sql("""
            INSERT INTO revision_request_fields (id,revision_request_id,field_key)
            VALUES (981,971,'own_file'),(982,972,'other_file')
            """).update();

        mvc.perform(get("/v1/me/submissions/931?scholarId=912").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.files.length()").value(1))
            .andExpect(jsonPath("$.success.data.files[0].id").value(961))
            .andExpect(jsonPath("$.success.data.files[0].originalName").value("own.pdf"))
            .andExpect(jsonPath("$.success.data.files[0].s3Key").doesNotExist())
            .andExpect(jsonPath("$.success.data.files[0].s3_key").doesNotExist())
            .andExpect(jsonPath("$.success.data.revision.reason").value("본인 보완 사유"))
            .andExpect(jsonPath("$.success.data.revision.fieldKeys.length()").value(1))
            .andExpect(jsonPath("$.success.data.revision.fieldKeys[0]").value("own_file"));
    }

    @Test
    void firstVisitReturnsNullSubmissionWithoutCreatingADraft() throws Exception {
        jdbc.sql("INSERT INTO request_targets (id,request_id,scholar_id) VALUES (944,933,911)").update();
        mvc.perform(get("/v1/me/submissions/933").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.submission").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.success.data.files").isEmpty())
            .andExpect(jsonPath("$.success.data.revision").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.success.data.privacyNotice").doesNotExist())
            .andExpect(jsonPath("$.success.data.request.privacyNotice").doesNotExist())
            .andExpect(jsonPath("$.success.data.internalMemo").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
            "SELECT COUNT(*) FROM submissions WHERE request_id=933 AND scholar_id=911")
            .query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT","SUBMITTED","RESUBMITTED","CONFIRMED"})
    void preservesNestedStatusVersionAndOmitsOldRevisions(String submissionStatus) throws Exception {
        jdbc.sql("UPDATE submissions SET status=:status WHERE id=951")
            .param("status",submissionStatus).update();
        jdbc.sql("INSERT INTO revision_requests (id,submission_id,reason,requested_by) VALUES (971,951,'이전 보완',910)")
            .update();
        mvc.perform(get("/v1/me/submissions/931").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.submission.status").value(submissionStatus))
            .andExpect(jsonPath("$.success.data.submission.version").value(1))
            .andExpect(jsonPath("$.success.data.submission.answers.value").value("본인 입력"))
            .andExpect(jsonPath("$.success.data.revision").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void returnsOrderedFieldsWithObjectOptionsAndNullableHelpText() throws Exception {
        jdbc.sql("""
            INSERT INTO request_fields (id,request_id,field_key,label,field_type,required,options,help_text,sort_order)
            VALUES (991,931,'proof','증명서','FILE',TRUE,NULL,NULL,20),
                (992,931,'scale','만점','SELECT_ONE',TRUE,
                '[{"value":"4.5","label":"4.5점 만점"}]' FORMAT JSON,'학교 만점을 선택하세요',10)
            """).update();
        mvc.perform(get("/v1/me/submissions/931").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.fields[0].fieldKey").value("scale"))
            .andExpect(jsonPath("$.success.data.fields[0].options[0].value").value("4.5"))
            .andExpect(jsonPath("$.success.data.fields[0].options[0].label").value("4.5점 만점"))
            .andExpect(jsonPath("$.success.data.fields[0].helpText").value("학교 만점을 선택하세요"))
            .andExpect(jsonPath("$.success.data.fields[1].fieldKey").value("proof"))
            .andExpect(jsonPath("$.success.data.fields[1].options").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.success.data.fields[1].helpText").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void closedRequestsRemainReadableWithoutChangingTheirSubmission() throws Exception {
        jdbc.sql("UPDATE requests SET status='CLOSED' WHERE id=931").update();
        mvc.perform(get("/v1/me/submissions/931").with(scholar(911)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.request.status").value("CLOSED"))
            .andExpect(jsonPath("$.success.data.submission.status").value("DRAFT"));
    }

    @Test
    void rejectsRequestsOutsideOwnTargets() throws Exception {
        mvc.perform(get("/v1/me/submissions/933?scholarId=912").with(scholar(911)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_A_TARGET"));
    }

    @ParameterizedTest
    @ValueSource(longs = {932,999})
    void hidesUnpublishedAndMissingRequests(long id) throws Exception {
        mvc.perform(get("/v1/me/submissions/{requestId}",id).with(scholar(911)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("REQUEST_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0","-1","abc"})
    void rejectsInvalidRequestIds(String id) throws Exception {
        mvc.perform(get("/v1/me/submissions/"+id).with(scholar(911)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void requiresScholarAuthentication() throws Exception {
        mvc.perform(get("/v1/me/submissions/931")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/v1/me/submissions/931").with(jwt().jwt(j -> j.subject("910"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }
}
