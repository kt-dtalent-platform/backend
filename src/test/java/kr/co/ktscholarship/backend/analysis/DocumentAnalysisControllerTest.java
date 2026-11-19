package kr.co.ktscholarship.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3와 업스테이지는 가짜로 바꾸고, OCR 결과는 합성 성적증명서 S01(2026-1: 평점 3.75, 14학점, 4.5 만점)을 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentAnalysisControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private OcrClient ocr;

    @MockitoBean
    private AnalysisFileReader files;

    private MockMvc mockMvc;

    /*
     * 담당자 1001, 장학생 1002(제출 1201 제출됨, 파일 1301 업로드 완료·1302 업로드 중), 장학생 1003(제출 1202 작성 중)
     * 요청 1101: 2026학년도 1학기
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("INSERT INTO programs (id, name) VALUES (1001, '프로그램')").update();
        jdbc.sql("INSERT INTO cohorts (id, program_id, name) VALUES (1006, 1001, '6기')").update();
        jdbc.sql("""
            INSERT INTO users (id, name, email, password_hash, role) VALUES
                (1001, '담당자', 'analysis-manager@example.test', 'test-only', 'MANAGER'),
                (1002, '장학생', 'analysis-1@example.test', 'test-only', 'SCHOLAR'),
                (1003, '다른 장학생', 'analysis-2@example.test', 'test-only', 'SCHOLAR')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles (user_id, cohort_id, school_name)
            SELECT id, 1006, '가람대학교' FROM users WHERE id IN (1002, 1003)
            """).update();
        jdbc.sql("""
            INSERT INTO request_templates (id, code, name, default_fields)
            VALUES (1008, 'TEST_ANALYSIS', '성적증명서', '[]' FORMAT JSON)
            """).update();
        jdbc.sql("""
            INSERT INTO requests (id, program_id, cohort_id, template_id, title, academic_year, semester,
                closes_at, status, created_by, published_at) VALUES
                (1101, 1001, 1006, 1008, '2026-1 성적', 2026, '1', '2099-12-31 14:59:59', 'IN_PROGRESS', 1001,
                    '2026-11-01 00:00:00')
            """).update();
        jdbc.sql("""
            INSERT INTO submissions (id, request_id, scholar_id, status, answers, submitted_at, version) VALUES
                (1201, 1101, 1002, 'SUBMITTED', '{"gpa":3.75,"gpa_scale":"4.5","credits":14}' FORMAT JSON,
                    '2026-11-10 00:00:00', 1),
                (1202, 1101, 1003, 'DRAFT', '{"gpa":3.0}' FORMAT JSON, NULL, 0)
            """).update();
        jdbc.sql("""
            INSERT INTO submission_files (id, submission_id, field_key, s3_key, original_name, content_type,
                size_bytes, upload_status) VALUES
                (1301, 1201, 'transcript', 'submissions/1201/a.png', '성적증명서.png', 'image/png', 2048, 'UPLOADED'),
                (1302, 1201, 'transcript', 'submissions/1201/b.png', '올리는 중.png', 'image/png', 10, 'PENDING'),
                (1303, 1202, 'transcript', 'submissions/1202/c.png', '작성 중.png', 'image/png', 10, 'UPLOADED')
            """).update();

        given(files.read(anyString())).willReturn(new byte[] {1, 2, 3});
        given(ocr.read(any(), anyString())).willReturn(samplePage());
    }

    @Test
    void managerAnalyzesSubmittedTranscriptWithoutChangingStatus() throws Exception {
        mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.submissionId").value(1201))
            .andExpect(jsonPath("$.success.data.fileId").value(1301))
            .andExpect(jsonPath("$.success.data.attemptNumber").value(1))
            .andExpect(jsonPath("$.success.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.success.data.overallResult").value("MATCH"))
            .andExpect(jsonPath("$.success.data.comparisons[*].field", contains("gpa", "gpa_scale", "credits")))
            .andExpect(jsonPath("$.success.data.comparisons[0].extracted").value("3.75"))
            .andExpect(jsonPath("$.success.data.outdated").value(false))
            .andExpect(jsonPath("$.success.data.failureReason").doesNotExist());

        Map<String, Object> saved = jdbc.sql("SELECT status, overall_result, requested_by, analyzer_version FROM document_analysis_results WHERE file_id = 1301")
            .query().singleRow();
        assertThat(saved).containsEntry("status", "COMPLETED").containsEntry("overall_result", "MATCH")
            .containsEntry("requested_by", 1001L);
        assertThat(jdbc.sql("SELECT status FROM submissions WHERE id = 1201").query(String.class).single())
            .isEqualTo("SUBMITTED");

        // 담당자 개별 검토 화면(제출 상세)에 결과가 붙는다
        mockMvc.perform(asManager(get("/v1/submissions/1201")))
            .andExpect(jsonPath("$.success.data.analysis.overallResult").value("MATCH"))
            .andExpect(jsonPath("$.success.data.analysis.comparisons[1].label").value("만점"));
    }

    @Test
    void reportsMismatchWhenEnteredValueDiffers() throws Exception {
        jdbc.sql("UPDATE submissions SET answers = '{\"gpa\":3.9,\"gpa_scale\":\"4.5\",\"credits\":14}' FORMAT JSON WHERE id = 1201").update();

        mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(jsonPath("$.success.data.overallResult").value("MISMATCH"))
            .andExpect(jsonPath("$.success.data.comparisons[0].result").value("MISMATCH"))
            .andExpect(jsonPath("$.success.data.comparisons[0].entered").value("3.9"));
    }

    @Test
    void reanalysisInvalidatesPreviousResult() throws Exception {
        long first = analysisId(mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis"))).andReturn());
        mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(jsonPath("$.success.data.attemptNumber").value(2));

        assertThat(jdbc.sql("SELECT invalidated_at FROM document_analysis_results WHERE id = :id").param("id", first)
            .query().singleRow().get("invalidated_at")).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_analysis_results WHERE submission_id = 1201 AND invalidated_at IS NULL")
            .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void resultBecomesOutdatedWhenAnswersChange() throws Exception {
        long id = analysisId(mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis"))).andReturn());

        jdbc.sql("UPDATE submissions SET answers = '{\"gpa\":3.8,\"gpa_scale\":\"4.5\",\"credits\":14}' FORMAT JSON WHERE id = 1201").update();

        mockMvc.perform(asManager(get("/v1/analysis-results/" + id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.outdated").value(true));
        // 상세에는 오래된 결과를 보여 주지 않는다
        mockMvc.perform(asManager(get("/v1/submissions/1201")))
            .andExpect(jsonPath("$.success.data.analysis").doesNotExist());
    }

    @Test
    void recordsFailureWhenOcrOrStorageIsUnavailable() throws Exception {
        given(ocr.read(any(), anyString())).willThrow(new OcrUnavailableException("OCR_NOT_CONFIGURED"));
        mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.status").value("FAILED"))
            .andExpect(jsonPath("$.success.data.overallResult").value("UNREADABLE"))
            .andExpect(jsonPath("$.success.data.failureReason").value("OCR_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.success.data.comparisons[*].result", contains("UNREADABLE", "UNREADABLE", "UNREADABLE")));

        given(files.read(anyString())).willThrow(new BusinessException(ErrorCode.STORAGE_UNAVAILABLE));
        mockMvc.perform(asManager(post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(jsonPath("$.success.data.failureReason").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void scholarCanAnalyzeAndReadOnlyOwnSubmission() throws Exception {
        long id = analysisId(mockMvc.perform(asScholar(1002, post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(status().isOk()).andReturn());
        mockMvc.perform(asScholar(1002, get("/v1/analysis-results/" + id)))
            .andExpect(status().isOk());

        mockMvc.perform(asScholar(1003, post("/v1/submissions/1201/files/1301/analysis")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(asScholar(1003, get("/v1/analysis-results/" + id)))
            .andExpect(status().isForbidden());
        // 작성 중이어도 본인은 제출 전에 분석해 볼 수 있다
        mockMvc.perform(asScholar(1003, post("/v1/submissions/1202/files/1303/analysis")))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsFilesThatCannotBeAnalyzed() throws Exception {
        // 업로드가 끝나지 않은 파일, 다른 제출의 파일, 없는 파일
        for (String path : new String[] {"/v1/submissions/1201/files/1302/analysis",
            "/v1/submissions/1201/files/1303/analysis", "/v1/submissions/1201/files/9999/analysis"}) {
            mockMvc.perform(asManager(post(path)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
        }
        // 담당자에게 작성 중 제출은 없는 제출
        mockMvc.perform(asManager(post("/v1/submissions/1202/files/1303/analysis")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_analysis_results").query(Long.class).single()).isZero();
    }

    @Test
    void returnsNotFoundAndUnauthorized() throws Exception {
        mockMvc.perform(asManager(get("/v1/analysis-results/999999")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ANALYSIS_NOT_FOUND"));
        mockMvc.perform(post("/v1/submissions/1201/files/1301/analysis"))
            .andExpect(status().isUnauthorized());
    }

    // ---------- 도움 메서드 ----------

    private static OcrPage samplePage() throws Exception {
        try (InputStream in = DocumentAnalysisControllerTest.class.getResourceAsStream("/ocr-samples/S01_v00.json")) {
            return UpstageOcrParser.parse(JsonMapper.builder().build(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static long analysisId(MvcResult result) throws Exception {
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
            "$.success.data.id").toString());
    }

    private MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().jwt(token -> token.subject("1001")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    private MockHttpServletRequestBuilder asScholar(long id, MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().jwt(token -> token.subject(String.valueOf(id)))
            .authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR")));
    }
}
