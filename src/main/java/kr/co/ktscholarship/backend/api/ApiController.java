package kr.co.ktscholarship.backend.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import kr.co.ktscholarship.backend.api.AuthService.Actor;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
// API 연결
public class ApiController {
  // DB 접근
  private final Database d;
  // 인증 처리
  private final AuthService auth;
  // 제출 업무
  private final WorkflowService w;
  // 파일 처리
  private final FileService files;
  // 문서 분석
  private final AnalysisService analysis;
  // 엑셀 출력
  private final ExportService exports;
  // 요청 정보
  private final HttpServletRequest request;

  // 의존성 주입
  public ApiController(
      Database d,
      AuthService auth,
      WorkflowService w,
      FileService files,
      AnalysisService analysis,
      ExportService exports,
      HttpServletRequest request) {
    this.d = d;
    this.auth = auth;
    this.w = w;
    this.files = files;
    this.analysis = analysis;
    this.exports = exports;
    this.request = request;
  }

  // 인증 사용자 조회
  private Actor actor() {
    return (Actor) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  // 성공 응답
  private Object ok(Object value) {
    var envelope =
        ApiAdvice.envelope(
            d.output(value instanceof Database.Page p ? p.items() : value),
            null,
            null,
            request.getRequestURI());
    if (value instanceof Database.Page p) {
      @SuppressWarnings("unchecked")
      var meta = new LinkedHashMap<>((Map<String, Object>) envelope.get("meta"));
      meta.put("nextCursor", p.nextCursor());
      meta.put("hasMore", p.hasMore());
      envelope.put("meta", meta);
    }
    return envelope;
  }

  // 로그인 입력
  public record Login(
      @NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 72) String password) {}

  // 토큰 갱신 입력
  public record Refresh(@NotBlank @Size(max = 128) String refreshToken) {}

  // 비밀번호 변경 입력
  public record Password(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 12, max = 72) String newPassword,@Size(max=128)String mfaCode) {}

  // 다중 인증 입력
  public record Mfa(@NotBlank @Size(max=128)String challengeToken,@Size(max=128)String code){}
  // 민감 작업 재인증
  public record Reauthentication(@NotBlank @Size(max=72)String currentPassword,@Size(max=128)String mfaCode){}

  // 프로그램 입력
  public record Program(
      @NotBlank @Size(max = 100) String name, @Size(max = 4000) String description) {}

  // 기수 입력
  public record Cohort(@NotBlank @Size(max = 50) String name) {}

  // 장학생 입력
  public record Scholar(
      @Positive long cohortId,
      @NotBlank @Email @Size(max = 255) String email,
      @NotBlank @Size(max = 50) String name,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 100) String schoolName) {}

  // 업무 생성 입력
  public record NewRequest(
      @Positive long programId,
      Long cohortId,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 8000) String description,
      @Min(2000) @Max(2200) int academicYear,
      @NotBlank String semester,
      @NotNull Instant closesAt,
      @Size(max = 4000) String completionMessage) {}

  // 업무 수정 입력
  public record EditRequest(
      @Min(0) int version,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 8000) String description,
      @NotNull Instant closesAt) {}

  // 수정 버전
  public record Version(@Min(0) int version) {}

  // 성적 입력
  public record Answers(@Min(0) int version, @NotNull @Size(max = 3) Map<String, Object> answers) {}

  // 제출 입력
  public record Submit(
      @Min(0) int version,
      Boolean noticeAcknowledged,
      Boolean manualReviewRequested,
      @Size(max = 2000) String manualReviewReason) {}

  // 보완 요청 입력
  public record Revision(
      @Min(0) int version,
      @NotBlank @Size(max = 2000) String reason,
      @NotEmpty @Size(max = 4) List<String> fields) {}

  // 분석 요청 입력
  public record Analyze(@Positive long fileId, @Min(0) int version, boolean noticeAcknowledged) {}

  @PostMapping("/auth/login")
  // 로그인
  public Object login(@Valid @RequestBody Login b) {
    return ok(auth.login(b.email(), b.password()));
  }

  @PostMapping("/auth/refresh")
  // 토큰 갱신
  public Object refresh(@Valid @RequestBody Refresh b) {
    return ok(auth.refresh(b.refreshToken()));
  }

  @PostMapping("/auth/logout")
  // 전체 로그아웃
  public Object logout() {
    auth.logout(actor());
    return ok(null);
  }

  @GetMapping("/users/me")
  // 본인 정보 조회
  public Object me() {
    return ok(
        d.one(
            "SELECT id,email,name,role,status,last_login_at FROM users WHERE id=?", actor().id()));
  }

  @PutMapping("/users/me/password")
  // 비밀번호 변경
  public Object password(@Valid @RequestBody Password b) {
    auth.password(actor(), b.currentPassword(), b.newPassword(),b.mfaCode());
    return ok(null);
  }

  @GetMapping("/programs")
  // 프로그램 목록
  public Object programs(
      @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int size) {
    return ok(w.programs(actor(), after, size));
  }

  @PostMapping("/programs")
  // 프로그램 생성
  public Object program(@Valid @RequestBody Program b) {
    return ok(w.createProgram(actor(), b.name(), b.description()));
  }

  @GetMapping("/programs/{id}/cohorts")
  // 기수 목록
  public Object cohorts(
      @PathVariable long id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "50") int size) {
    return ok(w.cohorts(actor(), id, after, size));
  }

  @PostMapping("/programs/{id}/cohorts")
  // 기수 생성
  public Object cohort(@PathVariable long id, @Valid @RequestBody Cohort b) {
    return ok(w.createCohort(actor(), id, b.name()));
  }

  @GetMapping("/cohorts/{id}/scholars")
  // 장학생 목록
  public Object scholars(
      @PathVariable long id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "50") int size) {
    return ok(w.scholars(actor(), id, after, size));
  }

  @PostMapping("/scholars")
  // 장학생 등록
  public Object scholar(@Valid @RequestBody Scholar b) {
    return ok(
        w.createScholar(actor(), b.cohortId(), b.email(), b.name(), b.password(), b.schoolName()));
  }

  @GetMapping("/request-templates")
  // 활성 양식
  public Object templates() {
    return ok(w.templates());
  }

  @PostMapping("/auth/mfa/enroll")
  // 인증 앱 등록
  public Object enroll(@Valid @RequestBody Mfa body){return ok(auth.enroll(body.challengeToken()));}

  @PostMapping("/auth/mfa/enroll/confirm")
  // 인증 앱 등록 확인
  public Object confirmEnrollment(@Valid @RequestBody Mfa body){return ok(auth.confirmEnrollment(body.challengeToken(),body.code()));}

  @PostMapping("/auth/mfa/verify")
  // 다중 인증 확인
  public Object verifyMfa(@Valid @RequestBody Mfa body){return ok(auth.verifyMfa(body.challengeToken(),body.code()));}

  @PostMapping("/users/me/mfa/recovery-codes")
  // 복구 코드 재발급
  public Object recoveryCodes(@Valid @RequestBody Reauthentication body){return ok(auth.replaceRecoveryCodes(actor(),body.currentPassword(),body.mfaCode()));}

  @DeleteMapping("/users/me/mfa")
  // 인증 앱 재등록
  public Object resetMfa(@Valid @RequestBody Reauthentication body){auth.resetMfa(actor(),body.currentPassword(),body.mfaCode());return ok(null);}

  @GetMapping("/users/me/sessions")
  // 본인 세션 목록
  public Object sessions(){return ok(auth.sessions(actor()));}

  @DeleteMapping("/users/me/sessions/{id}")
  // 본인 세션 폐기
  public Object endSession(@PathVariable long id,@Valid @RequestBody Reauthentication body){auth.endSession(actor(),id,body.currentPassword(),body.mfaCode());return ok(null);}

  @PostMapping("/scholars/{id}/temporary-password")
  // 임시 비밀번호 재발급
  public Object resetPassword(@PathVariable long id,@Valid @RequestBody Reauthentication body){return ok(auth.resetScholar(actor(),id,body.currentPassword(),body.mfaCode()));}

  @GetMapping("/requests")
  // 업무 목록
  public Object requests(
      @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int size) {
    return ok(w.requests(actor(), after, size));
  }

  @PostMapping("/requests")
  // 업무 생성
  public Object createRequest(@Valid @RequestBody NewRequest b) {
    return ok(
        w.createRequest(
            actor(),
            b.programId(),
            b.cohortId(),
            b.title(),
            b.description(),
            b.academicYear(),
            b.semester(),
            b.closesAt(),
            b.completionMessage()));
  }

  @GetMapping("/requests/{id}")
  // 업무 상세
  public Object getRequest(@PathVariable long id) {
    return ok(w.request(actor(), id, false));
  }

  @PutMapping("/requests/{id}")
  // 공개 전 업무 수정
  public Object editRequest(@PathVariable long id, @Valid @RequestBody EditRequest b) {
    return ok(w.editRequest(actor(), id, b.version(), b.title(), b.description(), b.closesAt()));
  }

  @GetMapping("/requests/{id}/fields")
  // 입력 항목
  public Object fields(@PathVariable long id) {
    return ok(w.fields(actor(), id));
  }

  @PostMapping("/requests/{id}/publish")
  // 업무 공개
  public Object publish(@PathVariable long id, @Valid @RequestBody Version b) {
    return ok(w.publish(actor(), id, b.version()));
  }

  @PostMapping("/requests/{id}/close")
  // 업무 마감
  public Object close(@PathVariable long id, @Valid @RequestBody Version b) {
    return ok(w.close(actor(), id, b.version()));
  }

  @GetMapping("/requests/{id}/targets")
  // 대상자 목록
  public Object targets(
      @PathVariable long id,
      @RequestParam(defaultValue = "false") boolean notSubmittedOnly,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "50") int size) {
    return ok(w.targets(actor(), id, after, size, notSubmittedOnly));
  }

  @GetMapping("/requests/{id}/submissions")
  // 제출 목록
  public Object submissions(
      @PathVariable long id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "50") int size) {
    return ok(w.submissions(actor(), id, after, size));
  }

  @PostMapping("/requests/{id}/submissions")
  // 작성 시작
  public Object start(@PathVariable long id) {
    return ok(w.start(actor(), id));
  }

  @GetMapping("/requests/{id}/my-submission")
  // 본인 제출
  public Object mine(@PathVariable long id) {
    ApiException.require(!actor().manager(), 403, "FORBIDDEN", "장학생 전용 API입니다.");
    w.request(actor(), id, false);
    var rows =
        d.rows("SELECT * FROM submissions WHERE request_id=? AND scholar_id=?", id, actor().id());
    return ok(rows.isEmpty() ? null : rows.getFirst());
  }

  @GetMapping("/submissions/{id}")
  // 제출 접근 검사
  public Object submission(@PathVariable long id) {
    return ok(w.submission(actor(), id, false));
  }

  @PutMapping("/submissions/{id}/answers")
  // 입력값 저장
  public Object answers(@PathVariable long id, @Valid @RequestBody Answers b) {
    return ok(w.save(actor(), id, b.version(), b.answers()));
  }

  @PostMapping("/submissions/{id}/submit")
  // 최종 제출
  public Object submit(@PathVariable long id, @Valid @RequestBody Submit b) {
    return ok(
        w.submit(
            actor(),
            id,
            b.version(),
            Boolean.TRUE.equals(b.noticeAcknowledged()),
            Boolean.TRUE.equals(b.manualReviewRequested()),
            b.manualReviewReason()));
  }

  @PostMapping("/submissions/{id}/confirm")
  // 확인 완료
  public Object confirm(@PathVariable long id, @Valid @RequestBody Version b) {
    return ok(w.review(actor(), id, b.version(), true, null, null));
  }

  @PostMapping("/submissions/{id}/revision-requests")
  // 보완 요청
  public Object revision(@PathVariable long id, @Valid @RequestBody Revision b) {
    return ok(w.review(actor(), id, b.version(), false, b.reason(), b.fields()));
  }

  @GetMapping("/submissions/{id}/revision-requests")
  // 보완 이력
  public Object revisions(@PathVariable long id) {
    return ok(w.revisions(actor(), id));
  }

  @GetMapping("/submissions/{id}/histories")
  // 변경 이력
  public Object histories(@PathVariable long id) {
    return ok(w.histories(actor(), id));
  }

  @GetMapping("/submissions/{id}/files")
  // 파일 목록
  public Object files(@PathVariable long id) {
    return ok(files.list(actor(), id));
  }

  @PostMapping(value = "/submissions/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  // 파일 업로드
  public Object upload(
      @PathVariable long id, @RequestParam int version, @RequestPart("file") MultipartFile file)
      throws IOException {
    return ok(files.upload(actor(), id, version, file));
  }

  @DeleteMapping("/files/{id}")
  // 미제출 파일 삭제
  public Object deleteFile(@PathVariable long id, @RequestParam int version) throws IOException {
    return ok(files.delete(actor(), id, version));
  }

  @GetMapping("/files/{id}/download")
  // 파일 다운로드
  public ResponseEntity<byte[]> download(@PathVariable long id) throws IOException {
    var download = files.download(actor(), id);
    var file = download.file();
    byte[] data = download.content();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(Database.text(file, "content_type")))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(Database.text(file, "original_name"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(data);
  }

  @PostMapping("/submissions/{id}/analyses")
  // 문서 분석 요청
  public Object analyze(@PathVariable long id, @Valid @RequestBody Analyze b) {
    return ok(analysis.analyze(actor(), id, b.fileId(), b.version(), b.noticeAcknowledged()));
  }

  @GetMapping("/submissions/{id}/analyses")
  // 분석 이력
  public Object analyses(@PathVariable long id) {
    return ok(analysis.list(actor(), id));
  }

  @GetMapping("/analyses/{id}")
  // 분석 상세
  public Object analysis(@PathVariable long id) {
    return ok(analysis.result(actor(), id));
  }

  @GetMapping("/requests/{id}/submissions/export.xlsx")
  // 제출 현황 출력
  public ResponseEntity<byte[]> export(@PathVariable long id,
      @RequestParam(defaultValue = "false") boolean notSubmittedOnly) throws IOException {
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"submissions.xlsx\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(exports.export(actor(), id, notSubmittedOnly));
  }
}
