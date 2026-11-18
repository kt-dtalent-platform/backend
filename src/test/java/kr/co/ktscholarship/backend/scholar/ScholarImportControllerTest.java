package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class ScholarImportControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper mapper;
    @Autowired ScholarImportExcel excel;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO programs (id,name) VALUES (8601,'일괄 등록 검증')").update();
        jdbc.sql("INSERT INTO cohorts (id,program_id,name) VALUES (8606,8601,'검증 기수')").update();
        jdbc.sql("""
            INSERT INTO users (id,name,email,password_hash,role) VALUES
                (8681,'기존 학생','import-existing@example.test','test-only','SCHOLAR')
            """).update();
        jdbc.sql("INSERT INTO scholar_profiles (user_id,cohort_id,school_name) VALUES (8681,8606,'기존 학교')").update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM scholar_profiles WHERE cohort_id=8606").update();
        jdbc.sql("DELETE FROM users WHERE email LIKE 'import-%@example.test'").update();
        jdbc.sql("DELETE FROM cohorts WHERE id=8606").update();
        jdbc.sql("DELETE FROM programs WHERE id=8601").update();
    }

    @Test
    void createsMultipleAccountsWithOneTimePasswordsAndSchoolRules() throws Exception {
        var response = mvc.perform(upload(file(
                row("첫 학생", "import-first@example.test", "선택한 학교", "3"),
                row("둘째 학생", "import-second@example.test", "목록 밖 학교", ""))))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success.data.created").value(2))
            .andExpect(jsonPath("$.success.data.items[0].row").value(2))
            .andReturn().getResponse();
        var items = mapper.readTree(response.getContentAsString()).path("success").path("data").path("items");
        for (var item : items) {
            long id = item.path("id").asLong();
            String password = item.path("temporaryPassword").asText();
            var account = jdbc.sql("SELECT password_hash,password_change_required FROM users WHERE id=:id")
                .param("id", id).query().singleRow();
            assertThat(encoder.matches(password, (String) account.get("password_hash"))).isTrue();
            assertThat(account.get("password_change_required")).isEqualTo(true);
        }
        assertThat(items.get(0).path("temporaryPassword").asText())
            .isNotEqualTo(items.get(1).path("temporaryPassword").asText());
        assertThat(profile("import-first@example.test"))
            .containsEntry("school_id",3L).containsEntry("school_name","가천대학교")
            .containsEntry("student_no","001234").containsEntry("phone","010-0012-3456");
        assertThat(profile("import-second@example.test"))
            .containsEntry("school_id",null).containsEntry("school_name","목록 밖 학교");
    }

    @Test
    void invalidLastRowRejectsWholeFileWithRowAndField() throws Exception {
        mvc.perform(upload(file(row("학생","import-first@example.test","학교",""), row("","bad-email","",""))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].row").value(3));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void existingEmailOnLastRowRollsBackEarlierAccountAndProfile() throws Exception {
        mvc.perform(upload(file(row("학생","import-first@example.test","학교",""),
                row("기존","import-existing@example.test","학교",""))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"))
            .andExpect(jsonPath("$.error.details[0].row").value(3))
            .andExpect(jsonPath("$.error.details[0].field").value("email"));
        assertThat(newAccounts()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM scholar_profiles WHERE cohort_id=8606").query(Long.class).single())
            .isEqualTo(1L);
    }

    @Test
    void unknownSchoolOnLastRowRollsBackWholeFile() throws Exception {
        mvc.perform(upload(file(row("학생","import-first@example.test","학교",""),
                row("학생","import-second@example.test","학교","999999"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].row").value(3))
            .andExpect(jsonPath("$.error.details[0].field").value("schoolId"));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void duplicateInsideFileIsCaseInsensitiveAndCreatesNothing() throws Exception {
        mvc.perform(upload(file(row("학생","import-first@example.test","학교",""),
                row("학생","IMPORT-FIRST@example.test","학교",""))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0].row").value(3));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void deletedEmailCanBeReused() throws Exception {
        jdbc.sql("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=8681").update();
        mvc.perform(upload(file(row("학생","import-existing@example.test","학교",""))))
            .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE email='import-existing@example.test'").query(Long.class).single())
            .isEqualTo(2L);
    }

    @Test
    void unknownCohortIs404AndCreatesNothing() throws Exception {
        mvc.perform(multipart("/v1/scholars/import").file(file(row("학생","import-first@example.test","학교","")))
                .param("cohortId","999999").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.details[0].field").value("cohortId"));
        assertThat(newAccounts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"formula", "numericPhone", "numericStudentNo", "badSchoolId", "header", "extraColumn", "twoSheets"})
    void rejectsUnsafeOrMalformedWorkbook(String kind) throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file(row("학생","import-first@example.test","학교","")).getBytes()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(1);
            switch (kind) {
                case "formula" -> row.getCell(0).setCellFormula("1+1");
                case "numericPhone" -> row.getCell(3).setCellValue(1012345678);
                case "numericStudentNo" -> row.getCell(5).setCellValue(1234);
                case "badSchoolId" -> row.getCell(6).setCellValue(1.5);
                case "header" -> sheet.getRow(0).getCell(0).setCellValue("잘못된 이름");
                case "extraColumn" -> row.createCell(7).setCellValue("잃어버리면 안 되는 값");
                case "twoSheets" -> workbook.createSheet("다른 시트");
                default -> throw new IllegalArgumentException(kind);
            }
            workbook.write(output);
            content = output.toByteArray();
        }
        mvc.perform(upload(new MockMultipartFile("file","invalid.xlsx","application/octet-stream",content)))
            .andExpect(status().isBadRequest());
        assertThat(newAccounts()).isZero();
    }

    @Test
    void templateHasExpectedHeadersAndTextColumns() throws Exception {
        var response = mvc.perform(get("/v1/scholars/import/template")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition","attachment; filename=scholars-import-template.xlsx"))
            .andReturn().getResponse();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            for (int i=0;i<7;i++) assertThat(sheet.getRow(0).getCell(i).getStringCellValue()).isEqualTo(ScholarImportExcel.HEADERS.get(i));
            assertThat(sheet.getColumnStyle(5).getDataFormatString()).isEqualTo("@");
        }
    }

    @Test
    void accepts200RowsForParsingButRejects201() throws Exception {
        String[][] rows = new String[200][];
        for (int i=0;i<200;i++) rows[i]=row("학생","import-"+i+"@example.test","학교","");
        assertThat(excel.read(file(rows),8606)).hasSize(200);
        rows = java.util.Arrays.copyOf(rows,201);
        rows[200]=row("학생","import-201@example.test","학교","");
        mvc.perform(upload(file(rows))).andExpect(status().isBadRequest());
        assertThat(newAccounts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "fake", "xls", "oversized", "headerOnly"})
    void rejectsInvalidFiles(String kind) throws Exception {
        var valid=file();
        var input=switch(kind) {
            case "empty" -> new MockMultipartFile("file","empty.xlsx","application/octet-stream",new byte[0]);
            case "fake" -> new MockMultipartFile("file","fake.xlsx","application/octet-stream","not an excel".getBytes());
            case "xls" -> new MockMultipartFile("file","file.xls","application/octet-stream",valid.getBytes());
            case "oversized" -> new MockMultipartFile("file","large.xlsx","application/octet-stream",new byte[1024*1024+1]);
            default -> valid;
        };
        mvc.perform(upload(input)).andExpect(status().isBadRequest());
    }

    @Test
    void requiresManagerForUploadAndTemplate() throws Exception {
        var file=file(row("학생","import-first@example.test","학교",""));
        mvc.perform(multipart("/v1/scholars/import").file(file).param("cohortId","8606"))
            .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/v1/scholars/import").file(file).param("cohortId","8606")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/v1/scholars/import/template").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void responseToStringDoesNotExposeTemporaryPassword() {
        var response=new ScholarImportResponse(1,List.of(new ScholarImportResponse.Item(2,1,"email@example.test","secret")));
        assertThat(response.toString()).doesNotContain("secret");
    }

    @Test
    void bulkJsonMatchesFrontendAndReturnsCreatedWithUsablePasswords() throws Exception {
        var response = mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("import-second@example.test"))))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success.data.created").value(2)).andReturn().getResponse();
        var items = mapper.readTree(response.getContentAsString()).path("success").path("data").path("items");
        for (var item : items) {
            var user = jdbc.sql("SELECT password_hash,password_change_required FROM users WHERE id=:id")
                .param("id", item.path("id").asLong()).query().singleRow();
            assertThat(encoder.matches(item.path("temporaryPassword").asText(), (String) user.get("password_hash"))).isTrue();
            assertThat(user.get("password_change_required")).isEqualTo(true);
        }
        assertThat(profile("import-first@example.test")).containsEntry("school_name", "직접 입력 학교")
            .containsEntry("school_id", null).containsEntry("student_no", "001234");
    }

    @Test
    void bulkJsonDuplicateEmailIsAStringListAndRollsBackPreviousRows() throws Exception {
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("import-existing@example.test"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"))
            .andExpect(jsonPath("$.error.details[0]").value("import-existing@example.test"));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonRejectsCaseInsensitiveDuplicatesAndInvalidRowsWithoutSaving() throws Exception {
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("IMPORT-FIRST@example.test"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.details[0]").value("IMPORT-FIRST@example.test"));
        mvc.perform(bulk(List.of(bulkRow("import-first@example.test"), bulkRow("invalid"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].row").value(3));
        assertThat(newAccounts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"cohortId\":8606,\"scholars\":[]}",
        "{\"cohortId\":8606,\"scholars\":[null]}",
        "{\"cohortId\":0,\"scholars\":[{}]}"
    })
    void bulkJsonRejectsInvalidEnvelope(String body) throws Exception {
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isBadRequest());
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonAllows500RowsButRejects501BeforeRegistration() throws Exception {
        var rows = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (int i = 0; i < 500; i++) rows.add(bulkRow("invalid"));
        mvc.perform(bulk(rows)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[499].row").value(501));
        rows.add(bulkRow("import-first@example.test"));
        mvc.perform(bulk(rows)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0]").value("scholars"));
        assertThat(newAccounts()).isZero();
    }

    @Test
    void bulkJsonRequiresManagerAndSupportsSelectedSchoolAndMissingCohort() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("cohortId",8606,"scholars",List.of(bulkRow("import-first@example.test"))));
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/v1/scholars/bulk").contentType("application/json").content(body.replace("8606", "999999"))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
            .andExpect(status().isNotFound());
        var row = new java.util.HashMap<>(bulkRow("import-first@example.test"));
        row.put("schoolId", 3);
        mvc.perform(bulk(List.of(row))).andExpect(status().isCreated());
        assertThat(profile("import-first@example.test")).containsEntry("school_id",3L).containsEntry("school_name","가천대학교");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bulk(List<java.util.Map<String,Object>> rows) throws Exception {
        return post("/v1/scholars/bulk").contentType("application/json")
            .content(mapper.writeValueAsString(java.util.Map.of("cohortId",8606,"scholars",rows)))
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    private java.util.Map<String,Object> bulkRow(String email) {
        var row = new java.util.HashMap<String,Object>();
        row.put("name","일괄 학생"); row.put("email",email); row.put("university","직접 입력 학교");
        row.put("phone",null); row.put("major",null); row.put("studentNo","001234");
        return row;
    }

    private MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/v1/scholars/import").file(file)
            .param("cohortId","8606").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    private MockMultipartFile file(String[]... values) throws Exception {
        try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(excel.template())); var output=new ByteArrayOutputStream()) {
            var sheet=workbook.getSheetAt(0);
            for(int i=0;i<values.length;i++) {
                var row=sheet.createRow(i+1);
                for(int j=0;j<7;j++) row.createCell(j).setCellValue(values[i][j]);
            }
            workbook.write(output);
            return new MockMultipartFile("file","scholars.xlsx","application/octet-stream",output.toByteArray());
        }
    }

    private String[] row(String name,String email,String school,String id) {
        return new String[]{name,email,school,"010-0012-3456","전공","001234",id};
    }

    private long newAccounts() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE email LIKE 'import-%@example.test' AND id<>8681").query(Long.class).single();
    }

    private java.util.Map<String,Object> profile(String email) {
        return jdbc.sql("SELECT p.* FROM scholar_profiles p JOIN users u ON u.id=p.user_id WHERE u.email=:email AND u.deleted_at IS NULL")
            .param("email",email).query().singleRow();
    }
}
