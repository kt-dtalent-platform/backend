package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScholarExportControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    @BeforeEach void seed() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs(id,name) VALUES(81001,'명단 검증')").update();
        jdbc.sql("INSERT INTO cohorts(id,program_id,name) VALUES(81001,81001,'2026기')").update();
        jdbc.sql("""
            INSERT INTO users(id,name,email,password_hash,role,deleted_at) VALUES
            (81001,'담당자','export-manager@example.test','test','MANAGER',NULL),
            (81002,'=SUM(1,2)','export-a@example.test','test','SCHOLAR',NULL),
            (81003,'휴학생','export-b@example.test','test','SCHOLAR',NULL),
            (81004,'탈퇴','export-deleted@example.test','test','SCHOLAR','2026-01-01')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles(user_id,cohort_id,school_name,student_no,phone,status,completed_year)
            VALUES(81002,81001,'한글학교','00123','010-1234-5678','COMPLETED',2026),
            (81003,81001,'다른학교','00456',NULL,'LEAVE',NULL),
            (81004,81001,'학교',NULL,NULL,'COMPLETED',2026)
            """).update();
    }
    MockHttpServletRequestBuilder request(String query) {
        return get("/v1/scholars/export"+query).with(jwt().jwt(j->j.subject("81001"))
            .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }
    @Test void returnsRealXlsxWithFiltersSelectedColumnsAndSafeText() throws Exception {
        var result=mvc.perform(request("?status=COMPLETED&cohortId=81001&columns=studentNo,phone,completedYear"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(result.getContentType()).startsWith("application/vnd.openxmlformats");
        assertThat(result.getHeader("Content-Disposition")).contains("attachment");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(result.getContentAsByteArray()))) {
            var sheet=workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(4);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("이름");
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("=SUM(1,2)");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("00123");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("010-****-5678");
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(2026);
        }
        assertThat(jdbc.sql("SELECT row_count FROM audit_logs WHERE actor_id=81001").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void supportsLeaveAndEmptyResults() throws Exception {
        for(String filter:new String[]{"LEAVE","ENROLLED"}) {
            var response=mvc.perform(request("?status="+filter+"&columns=status"))
                .andExpect(status().isOk()).andReturn().getResponse();
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                var sheet=workbook.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(filter.equals("LEAVE")?1:0);
                if(filter.equals("LEAVE")) assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("휴학");
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"?format=csv","?status=UNKNOWN","?status=LEAVE,", "?columns=passwordHash","?cohortId=0"})
    void rejectsUnsupportedInput(String query) throws Exception {
        mvc.perform(request(query)).andExpect(status().isBadRequest());
    }
    @Test void requiresManager() throws Exception {
        mvc.perform(get("/v1/scholars/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/scholars/export").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
    }
}
