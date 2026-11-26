package kr.co.ktscholarship.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.support.WorkflowFixture;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class SubmissionExportTest {
    @Autowired WebApplicationContext context;@Autowired JdbcClient jdbc;@Autowired DataSource source;
    WorkflowFixture fixture;MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkflowFixture(jdbc,source);fixture.seed();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup(){fixture.clean();}
    MockHttpServletRequestBuilder manager(String url) {
        return get(url).with(jwt().jwt(j->j.subject("70001")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
    int audits(){return jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE actor_id=70001").query(Integer.class).single();}
    @Test void workbookIncludesTargetsAndConfiguredFieldsAndKeepsIdentifiersAsText() throws Exception {
        fixture.sql("UPDATE submissions SET status='SUBMITTED',submitted_at='2026-11-01' WHERE request_id=71001");
        var result=mvc.perform(manager("/v1/requests/71001/export?columns=name,studentNo,phone,gpa,receipt"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("평점");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("다른학생");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("00456");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("010-****-5432");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("타인.pdf");
            assertThat(sheet.getRow(2).getCell(3).getNumericCellValue()).isEqualTo(3.5);
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEmpty();
        }
        assertThat(jdbc.sql("SELECT row_count FROM audit_logs WHERE actor_id=70001 AND action='EXPORT_SUBMISSIONS'").query(Integer.class).single()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"xlsx","csv"})
    void draftAnswersAndUploadedFileNamesAreNotExported(String format) throws Exception {
        fixture.sql("UPDATE submissions SET answers='{\"name\":\"홍길동\",\"gpa\":3.75,\"gpa_scale\":4.5,\"credits\":14}' FORMAT JSON WHERE id=72001");
        var response=mvc.perform(manager("/v1/requests/71001/export?format="+format+"&columns=name,status,gpa,gpaScale,credits,receipt,other_file"))
            .andExpect(status().isOk()).andReturn().getResponse();
        if(format.equals("xlsx")) {
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                var sheet=book.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(2);
                for(int r=1;r<=2;r++) {
                    assertThat(sheet.getRow(r).getCell(0).getStringCellValue()).isNotBlank();
                    assertThat(sheet.getRow(r).getCell(1).getStringCellValue()).isEqualTo("미제출");
                    for(int c=2;c<=6;c++) assertThat(sheet.getRow(r).getCell(c).getStringCellValue()).isEmpty();
                }
            }
        } else {
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"홍길동\",\"미제출\",\"\",\"\",\"\",\"\",\"\"")
                .doesNotContain("3.75","4.5","타인.pdf");
        }
        assertThat(jdbc.sql("SELECT answers FROM submissions WHERE id=72001").query(String.class).single()).contains("3.75");
    }

    @ParameterizedTest @ValueSource(strings={"SUBMITTED","RESUBMITTED","REVISION_REQUIRED","CONFIRMED"})
    void submittedAnswersRemainInExport(String state) throws Exception {
        fixture.sql("UPDATE submissions SET status='"+state+"' WHERE id=72003");
        var csv=mvc.perform(manager("/v1/requests/71002/export?format=csv&columns=name,gpa"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"홍길동\",\"3.0\"");
    }

    @Test void includesTargetsWithoutSubmissionAndSupportsWaitingAndMultipleStatuses() throws Exception {
        var result=mvc.perform(manager("/v1/requests/71002/export?format=csv&status=NOT_SUBMITTED,WAITING&columns=name,status,gpa,submittedAt"))
            .andExpect(status().isOk()).andReturn();
        String csv=result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 제출 일시는 UTC 글자가 아니라 한국 시각 (2026-11-01 00:00 UTC = 09:00 KST)
        assertThat(csv).contains("다른학생\",\"미제출\",\"\",\"\"","2026-11-01 09:00").doesNotContain("T00:00:00Z");
        var waiting=mvc.perform(manager("/v1/requests/71002/export?format=csv&status=WAITING"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(waiting).contains("홍길동").doesNotContain("다른학생");
    }
    @Test void gpaScaleColumnReadsAnswerKeyAndCreditsHeaderIsEarnedCredits() throws Exception {
        // 열 키는 gpaScale, 장학생 답의 키는 gpa_scale
        fixture.sql("UPDATE submissions SET answers='{\"name\":\"홍길동\",\"gpa\":3.0,\"gpa_scale\":\"4.5\",\"credits\":15}' FORMAT JSON WHERE id=72003");
        var csv=mvc.perform(manager("/v1/requests/71002/export?format=csv&status=WAITING&columns=name,gpa,gpaScale,credits"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"이름\",\"평점\",\"만점 기준\",\"취득 학점\"", "\"홍길동\",\"3.0\",\"4.5\",\"15\"");
    }
    @Test void treatsUntrustedCellAsTextAndEscapesCsvFormulasAndQuotes() throws Exception {
        fixture.sql("UPDATE users SET name='=1+1' WHERE id=70002");
        fixture.sql("UPDATE scholar_profiles SET major='쉼표,따옴표\"줄' WHERE user_id=70002");
        var xlsx=mvc.perform(manager("/v1/requests/71001/export?columns=name,major")).andExpect(status().isOk()).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(xlsx.getResponse().getContentAsByteArray()))) {
            var cell=book.getSheetAt(0).getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getStringCellValue()).isEqualTo("=1+1");
        }
        var csv=mvc.perform(manager("/v1/requests/71001/export?format=csv&columns=name,major"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff").contains("\"'=1+1\"","\"쉼표,따옴표\"\"줄\"");
    }
    @Test void rejectsUnknownFormatsColumnsAndStatusesBeforeAudit() throws Exception {
        for(String query:new String[]{"format=zip","columns=s3_key","columns=name,,email","status=INVALID"})
            mvc.perform(manager("/v1/requests/71001/export?"+query)).andExpect(status().isBadRequest());
        mvc.perform(manager("/v1/requests/99999/export")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/requests/71001/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/requests/71001/export").with(jwt().jwt(j->j.subject("70002")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
        assertThat(audits()).isZero();
    }
}
