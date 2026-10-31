package kr.co.ktscholarship.backend;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import kr.co.ktscholarship.backend.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// API 통합 검증
class ApiIntegrationTests {
  @Value("${local.server.port}")
  int port;

  @Autowired Database d;
  @Autowired AuthService auth;
  @Autowired PasswordEncoder passwords;
  @MockitoBean OcrClient ocr;
  // 테스트 비밀번호
  private static final String PASSWORD = "Integration-Test-Password!";
  private String manager, scholar, other, outsider;
  private HttpClient client;

  // HTTP 응답
  record Reply(int status, JsonNode json, byte[] bytes) {
    // 응답 자료
    JsonNode data() {
      return json.path("success").path("data");
    }
  }

  @BeforeEach
  // 테스트 자료 준비
  void fixture() throws Exception {
    org.mockito.Mockito.when(
            ocr.extract(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(new IllegalStateException("OCR_NOT_CONFIGURED"));
    for (String t :
        List.of(
            "document_analysis_results",
            "revision_request_fields",
            "revision_requests",
            "submission_histories",
            "submission_files",
            "submissions",
            "request_targets",
            "request_fields",
            "requests",
            "manager_programs",
            "scholar_profiles",
            "cohorts",
            "programs",
            "audit_logs",
            "access_tokens",
            "refresh_tokens",
            "auth_sessions",
            "auth_challenges",
            "mfa_recovery_codes",
            "file_deletion_jobs",
            "request_budgets",
            "users")) d.update("DELETE FROM " + t);
    String hash = passwords.encode(PASSWORD);
    for (long id = 10; id <= 13; id++)
      d.update(
          "INSERT INTO users(id,email,password_hash,name,role) VALUES(?,?,?,?,?)",
          id,
          "user" + id + "@example.test",
          hash,
          "사용자" + id,
          id == 10 || id == 13 ? "MANAGER" : "SCHOLAR");
    d.update("INSERT INTO programs(id,name) VALUES(20,'장학사업')");
    d.update("INSERT INTO cohorts(id,program_id,name) VALUES(30,20,'1기')");
    d.update("INSERT INTO manager_programs(id,manager_id,program_id) VALUES(40,10,20)");
    d.update(
        "INSERT INTO scholar_profiles(user_id,cohort_id,school_name)"
            + " VALUES(11,30,'테스트대학교'),(12,30,'다른대학교')");
    manager = token(10);
    scholar = token(11);
    other = token(12);
    outsider = token(13);
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  // HTTP 연결 종료
  void close() {
    client.close();
  }

  @SuppressWarnings("unchecked")
  // 보안 토큰
  private String token(long id) {
    return ((Map<String, Object>) auth.login("user" + id + "@example.test", PASSWORD))
        .get("accessToken")
        .toString();
  }

  // HTTP 요청
  private Reply call(String method, String path, String token, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
            .timeout(java.time.Duration.ofSeconds(40));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    var response =
        client.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(d.encode(body)))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    byte[] bytes = response.body();
    JsonNode json = null;
    if (response.headers().firstValue("content-type").orElse("").contains("json"))
      json = d.json.readTree(bytes);
    return new Reply(response.statusCode(), json, bytes);
  }

  private byte[] samplePdf() throws Exception {
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        var out = new java.io.ByteArrayOutputStream()) {
      pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      pdf.save(out);
      return out.toByteArray();
    }
  }

  // 파일 업로드 요청
  private Reply upload(long id, int version, String token, String filename, byte[] contents)
      throws Exception {
    String boundary = "integration-boundary";
    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + filename
            + "\"\r\nContent-Type: application/pdf\r\n\r\n";
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/v1/submissions/"
                        + id
                        + "/files?version="
                        + version))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(body),
                HttpRequest.BodyPublishers.ofByteArray(contents),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    return new Reply(response.statusCode(), d.json.readTree(response.body()), response.body());
  }

  // 공개 업무 준비
  private long request() throws Exception {
    var created =
        call(
            "POST",
            "/requests",
            manager,
            Map.of(
                "programId",
                20,
                "cohortId",
                30,
                "title",
                "성적 제출",
                "academicYear",
                2026,
                "semester",
                "1",
                "closesAt",
                Instant.now().plusSeconds(3600).toString()));
    assertThat(created.status()).as(created.json().toString()).isEqualTo(200);
    long id = created.data().path("id").asLong();
    assertThat(call("GET", "/requests/" + id + "/fields", manager, null).data().size())
        .isEqualTo(4);
    assertThat(call("POST", "/requests/" + id + "/publish", manager, Map.of("version", 0)).status())
        .isEqualTo(200);
    return id;
  }

  // 제출 초안 준비
  private long start(long request) throws Exception {
    var response = call("POST", "/requests/" + request + "/submissions", scholar, null);
    assertThat(response.status()).isEqualTo(200);
    return response.data().path("id").asLong();
  }

  // 성적 입력 예제
  private Map<String, Object> answers() {
    return Map.of("gpa", 4.1, "gpa_scale", 4.5, "credits", 18);
  }

  @Test
  // 제출 검토 재제출 검증
  void completeSubmissionRevisionConfirmationFlow() throws Exception {
    long request = request(), id = start(request);
    assertThat(start(request)).isEqualTo(id);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", answers()))
                .status())
        .isEqualTo(200);
    var saved = call("GET", "/submissions/" + id, scholar, null).data().path("answers");
    assertThat(saved.has("gpa_scale")).isTrue();
    assertThat(saved.has("gpaScale")).isFalse();
    var file = upload(id, 1, scholar, "certificate.pdf", samplePdf());
    assertThat(file.status()).as(file.json().toString()).isEqualTo(200);
    long fileId = file.data().path("id").asLong();
    var analysis =
        call(
            "POST",
            "/submissions/" + id + "/analyses",
            scholar,
            Map.of("fileId", fileId, "version", 2, "noticeAcknowledged", true));
    assertThat(analysis.status()).isEqualTo(200);
    assertThat(analysis.data().path("status").asString()).isEqualTo("FAILED");
    assertThat(analysis.data().path("failureReason").asString()).isEqualTo("OCR_NOT_CONFIGURED");
    var noManual =
        call(
            "POST",
            "/submissions/" + id + "/submit",
            scholar,
            Map.of("version", 2, "noticeAcknowledged", true, "manualReviewRequested", false));
    assertThat(noManual.status()).as(noManual.json().toString()).isEqualTo(409);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/submit",
                    scholar,
                    Map.of(
                        "version",
                        2,
                        "noticeAcknowledged",
                        true,
                        "manualReviewRequested",
                        true,
                        "manualReviewReason",
                        "인식 엔진 미연결"))
                .data()
                .path("status")
                .asString())
        .isEqualTo("SUBMITTED");
    assertThat(
            call("POST", "/submissions/" + id + "/confirm", manager, Map.of("version", 2)).status())
        .isEqualTo(409);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/revision-requests",
                    manager,
                    Map.of("version", 3, "reason", "성적 재확인", "fields", List.of("credits")))
                .status())
        .isEqualTo(200);
    var changed = new HashMap<String, Object>(answers());
    changed.put("gpa", 4.2);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 4, "answers", changed))
                .status())
        .isEqualTo(400);
    assertThat(upload(id, 4, scholar, "new.pdf", samplePdf()).status()).isEqualTo(400);
    changed.put("gpa", 4.1);
    changed.put("credits", 19);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 4, "answers", changed))
                .status())
        .isEqualTo(200);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/submit",
                    scholar,
                    Map.of(
                        "version",
                        5,
                        "noticeAcknowledged",
                        true,
                        "manualReviewRequested",
                        true,
                        "manualReviewReason",
                        "담당자 확인 요청"))
                .data()
                .path("status")
                .asString())
        .isEqualTo("RESUBMITTED");
    assertThat(
            call("GET", "/submissions/" + id + "/revision-requests", scholar, null)
                .data()
                .get(0)
                .path("resolvedAt")
                .isNull())
        .isFalse();
    assertThat(call("DELETE", "/files/" + fileId + "?version=6", scholar, null).status())
        .isEqualTo(409);
    assertThat(
            call("POST", "/submissions/" + id + "/confirm", manager, Map.of("version", 6))
                .data()
                .path("status")
                .asString())
        .isEqualTo("CONFIRMED");
    var histories = call("GET", "/submissions/" + id + "/histories", manager, null);
    assertThat(histories.data().size()).isGreaterThanOrEqualTo(7);
    assertThat(new String(histories.bytes(), StandardCharsets.UTF_8))
        .contains("snapshot")
        .doesNotContain("s3Key", "passwordHash", "tokenHash");
    var export = call("GET", "/requests/" + request + "/submissions/export.xlsx", manager, null);
    assertThat(export.status()).isEqualTo(200);
    assertThat(export.bytes()[0]).isEqualTo((byte) 'P');
    assertThat(d.exists("SELECT id FROM audit_logs WHERE action='EXPORT_SUBMISSIONS'")).isTrue();
  }

  @Test
  // 자료 접근 권한 검증
  void accessControlsApplyToEverySensitiveResource() throws Exception {
    long request = request(), id = start(request);
    assertThat(call("GET", "/users/me", null, null).status()).isEqualTo(401);
    assertThat(call("GET", "/submissions/" + id, other, null).status()).isEqualTo(403);
    assertThat(call("GET", "/submissions/" + id, outsider, null).status()).isEqualTo(403);
    assertThat(call("GET", "/submissions/" + id + "/histories", other, null).status())
        .isEqualTo(403);
    assertThat(call("GET", "/requests/" + request + "/targets", scholar, null).status())
        .isEqualTo(403);
    assertThat(
            call("GET", "/requests/" + request + "/submissions/export.xlsx", outsider, null)
                .status())
        .isEqualTo(403);
    assertThat(
            call("POST", "/submissions/" + id + "/confirm", scholar, Map.of("version", 0)).status())
        .isEqualTo(403);
    var file = upload(id, 0, scholar, "certificate.pdf", samplePdf());
    long fileId = file.data().path("id").asLong();
    assertThat(call("GET", "/files/" + fileId + "/download", other, null).status()).isEqualTo(403);
    assertThat(call("GET", "/files/" + fileId + "/download", outsider, null).status())
        .isEqualTo(403);
    assertThat(call("GET", "/files/" + fileId + "/download", manager, null).status())
        .isEqualTo(200);
  }

  @Test
  // 토큰 갱신 폐기 검증
  void refreshRotationLogoutAndPasswordRevocation() throws Exception {
    var login =
        call(
            "POST",
            "/auth/login",
            null,
            Map.of("email", "user11@example.test", "password", PASSWORD));
    assertThat(login.status()).isEqualTo(200);
    String refresh = login.data().path("refreshToken").asString();
    var rotated = call("POST", "/auth/refresh", null, Map.of("refreshToken", refresh));
    assertThat(rotated.status()).isEqualTo(200);
    assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", refresh)).status())
        .isEqualTo(401);
    assertThat(call("POST", "/auth/logout", scholar, null).status()).isEqualTo(200);
    assertThat(
            call("GET", "/users/me", rotated.data().path("accessToken").asString(), null).status())
        .isEqualTo(401);
    assertThat(
            call(
                    "POST",
                    "/auth/refresh",
                    null,
                    Map.of("refreshToken", rotated.data().path("refreshToken").asString()))
                .status())
        .isEqualTo(401);
    String newToken = token(11);
    assertThat(
            call(
                    "PUT",
                    "/users/me/password",
                    newToken,
                    Map.of("currentPassword", PASSWORD, "newPassword", "Another-Strong-Password!"))
                .status())
        .isEqualTo(200);
    assertThat(call("GET", "/users/me", newToken, null).status()).isEqualTo(401);
  }

  @Test
  // 로그인 실패 잠금 검증
  void accountLocksAfterFiveFailedPasswordsWithoutRollingBackCounter() throws Exception {
    for (int i = 0; i < 5; i++)
      assertThat(
              call(
                      "POST",
                      "/auth/login",
                      null,
                      Map.of("email", "user11@example.test", "password", "wrong"))
                  .status())
          .isEqualTo(401);
    assertThat(
            call(
                    "POST",
                    "/auth/login",
                    null,
                    Map.of("email", "user11@example.test", "password", PASSWORD))
                .status())
        .isEqualTo(401);
    assertThat(
            Database.number(
                d.one("SELECT failed_login_count FROM users WHERE id=11"), "failed_login_count"))
        .isEqualTo(5);
  }

  @Test
  void unsubmittedRosterMatchesWorkbook() throws Exception {
    long requestId = request();
    long draftId = start(requestId);
    var base = "/requests/" + requestId;
    var filter = "?notSubmittedOnly=true";
    var targets = call("GET", base + "/targets" + filter, manager, null).data();
    assertThat(targets.size()).isEqualTo(2);
    assertThat(targets.get(0).path("status").asString()).isEqualTo("DRAFT");
    assertThat(targets.get(1).path("status").asString()).isEqualTo("NOT_STARTED");
    d.update("UPDATE submissions SET status='SUBMITTED' WHERE id=?", draftId);
    targets = call("GET", base + "/targets" + filter + "&size=1", manager, null).data();
    assertThat(targets.size()).isEqualTo(1);
    var response = call("GET", base + "/submissions/export.xlsx" + filter, manager, null);
    assertThat(response.status()).isEqualTo(200);
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
        new java.io.ByteArrayInputStream(response.bytes()))) {
      var sheet = book.getSheetAt(0);
      assertThat(sheet.getLastRowNum()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(2).getStringCellValue())
          .isEqualTo(targets.get(0).path("email").asString())
          .isEqualTo("user12@example.test");
    }
  }

  @Test
  // 대상 명단 마감 검증
  void snapshotTargetsAndCloseAreEnforced() throws Exception {
    long request = request();
    var create =
        call(
            "POST",
            "/scholars",
            manager,
            Map.of(
                "cohortId",
                30,
                "email",
                "late@example.test",
                "name",
                "추가 장학생",
                "password",
                PASSWORD,
                "schoolName",
                "학교"));
    assertThat(create.status()).isEqualTo(200);
    var targets = call("GET", "/requests/" + request + "/targets", manager, null);
    assertThat(targets.status()).as(targets.json().toString()).isEqualTo(200);
    assertThat(targets.data().size()).isEqualTo(2);
    assertThat(
            call("POST", "/requests/" + request + "/publish", manager, Map.of("version", 1))
                .status())
        .isEqualTo(409);
    long id = start(request);
    assertThat(
            call("GET", "/requests/" + request + "/targets", manager, null)
                .data()
                .get(0)
                .path("notSubmitted")
                .asBoolean())
        .isTrue();
    assertThat(
            call("POST", "/requests/" + request + "/close", manager, Map.of("version", 1)).status())
        .isEqualTo(200);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", answers()))
                .status())
        .isEqualTo(409);
  }

  @Test
  // 파일 형식 버전 검증
  void fileValidationAndStaleVersionsAreRejected() throws Exception {
    long id = start(request());
    assertThat(upload(id, 0, scholar, "malware.pdf", "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8)).status())
        .isEqualTo(400);
    assertThat(upload(id, 0, scholar, "malware.exe", samplePdf()).status()).isEqualTo(400);
    var first = upload(id, 0, scholar, "first.pdf", samplePdf());
    assertThat(first.status()).isEqualTo(200);
    assertThat(upload(id, 0, scholar, "second.pdf", samplePdf()).status()).isEqualTo(409);
    var second = upload(id, 1, scholar, "second.pdf", samplePdf());
    assertThat(second.status()).isEqualTo(200);
    assertThat(
            d.exists(
                "SELECT id FROM submission_files WHERE id=? AND superseded_at IS NOT NULL",
                first.data().path("id").asLong()))
        .isTrue();
    assertThat(
            call(
                    "DELETE",
                    "/files/" + second.data().path("id").asLong() + "?version=2",
                    scholar,
                    null)
                .status())
        .isEqualTo(200);
  }

  @Test
  // 성적 항목 검증
  void invalidGradeAndUnknownFieldsAreRejected() throws Exception {
    long id = start(request());
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", Map.of("gpa", 5, "gpa_scale", 4.5)))
                .status())
        .isEqualTo(400);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", Map.of("role", "MANAGER")))
                .status())
        .isEqualTo(400);
    assertThat(
            call("POST", "/submissions/" + id + "/submit", scholar, Map.of("version", 0)).status())
        .isEqualTo(400);
  }

  @Test
  // 동시 수정 충돌 검증
  void concurrentUpdatesAllowExactlyOneVersionWinner() throws Exception {
    long id = start(request());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> update =
          () -> {
            gate.await();
            return call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", answers()))
                .status();
          };
      var first = executor.submit(update);
      var second = executor.submit(update);
      gate.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
  }

  @Test
  // 기수 담당 범위 검증
  void cohortScopedManagerCannotAccessOtherCohortOrWholeProgram() throws Exception {
    d.update("INSERT INTO cohorts(id,program_id,name) VALUES(31,20,'2기')");
    d.update(
        "INSERT INTO manager_programs(id,manager_id,program_id,cohort_id) VALUES(41,13,20,31)");
    long id = start(request());
    assertThat(call("GET", "/submissions/" + id, outsider, null).status()).isEqualTo(403);
    assertThat(call("POST", "/programs/20/cohorts", outsider, Map.of("name", "3기")).status())
        .isEqualTo(403);
    assertThat(call("GET", "/programs/20/cohorts", outsider, null).data().size()).isEqualTo(1);
    assertThat(call("GET", "/cohorts/30/scholars", outsider, null).status()).isEqualTo(403);
  }

  @Test
  // DB 제약 검증
  void databaseRejectsInvalidForeignKeyEnumAndDuplicateEmail() {
    assertThatThrownBy(
            () -> d.update("INSERT INTO cohorts(id,program_id,name) VALUES(99,999,'잘못된 기수')"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> d.update("UPDATE users SET role='ADMIN' WHERE id=11"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> d.update("UPDATE users SET email='user11@example.test' WHERE id=12"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  // UTC 시간 보존 검증
  void timestampsRoundTripAsUtcWithoutHostTimezoneShift() {
    Instant expected = Instant.parse("2026-10-31T04:00:00Z");
    d.update("UPDATE users SET last_login_at=? WHERE id=11", java.sql.Timestamp.from(expected));
    @SuppressWarnings("unchecked")
    var row = (Map<String, Object>) d.output(d.one("SELECT last_login_at FROM users WHERE id=11"));
    assertThat(row.get("lastLoginAt")).isEqualTo(expected.toString());
  }

  @Test
  // 분석 무효화 횟수 검증
  void matchingAnalysisIsInvalidatedByEditsAndAttemptsAreLimited() throws Exception {
    var extracted = new HashMap<String, Object>(answers());
    extracted.put("school", "테스트대학교");
    extracted.put("documentType", "GRADE_CERTIFICATE");
    extracted.put("academicYear", 2026);
    extracted.put("semester", "1");
    extracted.put("hasF", false);
    org.mockito.Mockito.doReturn(extracted)
        .when(ocr)
        .extract(
            org.mockito.ArgumentMatchers.any(byte[].class),
            org.mockito.ArgumentMatchers.anyString());
    long id = start(request());
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 0, "answers", answers()))
                .status())
        .isEqualTo(200);
    long file =
        upload(id, 1, scholar, "certificate.pdf", samplePdf()).data().path("id").asLong();
    var analyzed =
        call(
            "POST",
            "/submissions/" + id + "/analyses",
            scholar,
            Map.of("version", 2, "fileId", file, "noticeAcknowledged", true));
    assertThat(analyzed.data().path("overallResult").asString()).isEqualTo("MATCH");
    assertThat(analyzed.data().path("enteredValues").has("gpa_scale")).isTrue();
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 2, "answers", answers()))
                .status())
        .isEqualTo(200);
    assertThat(
            call("GET", "/analyses/" + analyzed.data().path("id").asLong(), scholar, null)
                .data()
                .path("invalidatedAt")
                .isNull())
        .isFalse();
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/submit",
                    scholar,
                    Map.of("version", 3, "noticeAcknowledged", true))
                .status())
        .isEqualTo(409);
    for (int i = 0; i < 4; i++)
      assertThat(
              call(
                      "POST",
                      "/submissions/" + id + "/analyses",
                      scholar,
                      Map.of("version", 3, "fileId", file, "noticeAcknowledged", true))
                  .status())
          .isEqualTo(200);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/analyses",
                    scholar,
                    Map.of("version", 3, "fileId", file, "noticeAcknowledged", true))
                .status())
        .isEqualTo(409);
    assertThat(
            call("GET", "/analyses/" + analyzed.data().path("id").asLong(), other, null).status())
        .isEqualTo(403);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/submit",
                    scholar,
                    Map.of("version", 3, "noticeAcknowledged", true))
                .status())
        .isEqualTo(200);
  }

  @Test
  // 목록 조회 범위 검증
  void paginationAndProgramProvisioningPreserveScope() throws Exception {
    long id = request();
    var first = call("GET", "/requests/" + id + "/targets?size=1", manager, null);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.data().size()).isEqualTo(1);
    assertThat(first.json().path("meta").path("hasMore").asBoolean()).isTrue();
    long cursor = first.json().path("meta").path("nextCursor").asLong();
    var second = call("GET", "/requests/" + id + "/targets?size=1&after=" + cursor, manager, null);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.data().size()).isEqualTo(1);
    assertThat(second.json().path("meta").path("hasMore").asBoolean()).isFalse();
    assertThat(second.data().get(0).path("scholarId").asLong())
        .isNotEqualTo(first.data().get(0).path("scholarId").asLong());
    assertThat(call("GET", "/requests?size=201", manager, null).status()).isEqualTo(400);
    var program = call("POST", "/programs", manager, Map.of("name", "새 사업"));
    assertThat(program.status()).isEqualTo(200);
    long programId = program.data().path("id").asLong();
    assertThat(
            call("POST", "/programs/" + programId + "/cohorts", manager, Map.of("name", "새 기수"))
                .status())
        .isEqualTo(200);
    assertThat(
            call("POST", "/programs/" + programId + "/cohorts", outsider, Map.of("name", "다른 기수"))
                .status())
        .isEqualTo(403);
    assertThat(call("POST", "/programs", scholar, Map.of("name", "금지")).status()).isEqualTo(403);
  }

  @Test
  // 누적 업로드 한도 검증
  void storageQuotaBlocksUnlimitedReplacementUploads() throws Exception {
    long id = start(request());
    d.update(
        "INSERT INTO"
            + " submission_files(id,submission_id,field_key,s3_key,original_name,content_type,size_bytes,upload_status)"
            + " VALUES(?,?,'certificate','quota-fixture','old.pdf','application/pdf',?,'UPLOADED')",
        d.id(),
        id,
        50L * 1024 * 1024);
    assertThat(upload(id, 0, scholar, "new.pdf", samplePdf()).status()).isEqualTo(413);
    assertThat(Database.number(d.one("SELECT version FROM submissions WHERE id=?", id), "version"))
        .isZero();
  }

  @Test
  // 마감 후 보완 재제출 검증
  void explicitRevisionCanBeCompletedAfterDeadlineWithoutOpeningNewSubmissions() throws Exception {
    long request = request(), id = start(request);
    call(
        "PUT",
        "/submissions/" + id + "/answers",
        scholar,
        Map.of("version", 0, "answers", answers()));
    upload(id, 1, scholar, "certificate.pdf", samplePdf());
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/submit",
                    scholar,
                    Map.of(
                        "version",
                        2,
                        "noticeAcknowledged",
                        true,
                        "manualReviewRequested",
                        true,
                        "manualReviewReason",
                        "수동 확인"))
                .status())
        .isEqualTo(200);
    assertThat(
            call("POST", "/requests/" + request + "/close", manager, Map.of("version", 1)).status())
        .isEqualTo(200);
    d.update(
        "UPDATE requests SET closes_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
        request);
    assertThat(call("POST", "/requests/" + request + "/submissions", other, null).status())
        .isEqualTo(409);
    assertThat(
            call(
                    "POST",
                    "/submissions/" + id + "/revision-requests",
                    manager,
                    Map.of("version", 3, "reason", "학점 정정", "fields", List.of("credits")))
                .status())
        .isEqualTo(200);
    var updated = new HashMap<String, Object>(answers());
    updated.put("credits", 19);
    assertThat(
            call(
                    "PUT",
                    "/submissions/" + id + "/answers",
                    scholar,
                    Map.of("version", 4, "answers", updated))
                .status())
        .isEqualTo(200);
    var submitted =
        call(
            "POST",
            "/submissions/" + id + "/submit",
            scholar,
            Map.of(
                "version",
                5,
                "noticeAcknowledged",
                true,
                "manualReviewRequested",
                true,
                "manualReviewReason",
                "학점 보완"));
    assertThat(submitted.status()).isEqualTo(200);
    assertThat(submitted.data().path("isLate").asBoolean()).isTrue();
  }
}
