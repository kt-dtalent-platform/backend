package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.ApiException.require;
import static kr.co.ktscholarship.backend.api.Database.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import kr.co.ktscholarship.backend.api.AuthService.Actor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
// 제출 업무
public class WorkflowService {
  // 성적 항목
  private static final Set<String> ANSWER_KEYS = Set.of("gpa", "gpa_scale", "credits");
  // 전체 항목
  private static final Set<String> FIELD_KEYS =
      Set.of("gpa", "gpa_scale", "credits", "certificate");
  // DB 접근
  private final Database d;
  // 비밀번호 해시
  private final PasswordEncoder passwords;

  // 의존성 주입
  public WorkflowService(Database d, PasswordEncoder passwords) {
    this.d = d;
    this.passwords = passwords;
  }

  // 담당자 권한
  public void manager(Actor a) {
    require(a.manager(), 403, "FORBIDDEN", "담당자 권한이 필요합니다.");
  }

  // 담당 범위 검사
  public void scope(Actor a, long program, Long cohort) {
    manager(a);
    require(
        d.exists(
            "SELECT id FROM manager_programs WHERE manager_id=? AND program_id=? AND (cohort_id IS"
                + " NULL OR cohort_id=?)",
            a.id(),
            program,
            cohort),
        403,
        "FORBIDDEN",
        "담당 범위가 아닙니다.");
  }

  // 업무 접근 검사
  public Map<String, Object> request(Actor a, long id, boolean lock) {
    var r = d.one("SELECT * FROM requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    require(!RetentionService.expired(r),410,"RETENTION_EXPIRED","보관 기간이 만료된 업무입니다.");
    if (a.manager())
      scope(a, number(r, "program_id"), r.get("cohort_id") == null ? null : number(r, "cohort_id"));
    else
      require(
          !"DRAFT".equals(r.get("status"))
              && d.exists(
                  "SELECT id FROM request_targets WHERE request_id=? AND scholar_id=?", id, a.id()),
          403,
          "FORBIDDEN",
          "대상 업무가 아닙니다.");
    return r;
  }

  // 제출 접근 검사
  public Map<String, Object> submission(Actor a, long id, boolean lock) {
    var s = d.one("SELECT * FROM submissions WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (a.manager()) request(a, number(s, "request_id"), false);
    else {
      require(number(s, "scholar_id") == a.id(), 403, "FORBIDDEN", "본인 제출만 접근할 수 있습니다.");
      var retention = d.one("SELECT closes_at,purged_at FROM requests WHERE id=?", s.get("request_id"));
      require(!RetentionService.expired(retention), 410, "RETENTION_EXPIRED", "보관 기간이 만료된 제출입니다.");
    }
    return s;
  }

  // 제출 수정 검사
  public void writable(Actor a, Map<String, Object> s) {
    require(
        !a.manager() && number(s, "scholar_id") == a.id(), 403, "FORBIDDEN", "장학생 본인만 수정할 수 있습니다.");
    require(
        Set.of("DRAFT", "REVISION_REQUIRED").contains(text(s, "status")),
        409,
        "INVALID_STATE",
        "현재 제출 상태에서는 수정할 수 없습니다.");
    var r = request(a, number(s, "request_id"), false);
    boolean revision =
        "REVISION_REQUIRED".equals(s.get("status"))
            && d.exists(
                "SELECT id FROM revision_requests WHERE submission_id=? AND resolved_at IS NULL",
                s.get("id"));
    require(
        revision
            || ("IN_PROGRESS".equals(r.get("status"))
                && d.exists(
                    "SELECT id FROM requests WHERE id=? AND closes_at>?",
                    number(r, "id"),
                    d.now())),
        409,
        "REQUEST_CLOSED",
        "마감된 업무입니다.");
  }

  // 동시 수정 검사
  public void version(Map<String, Object> row, int version) {
    require(number(row, "version") == version, 409, "VERSION_CONFLICT", "내용이 변경되었습니다. 새로 조회해 주세요.");
  }

  // 프로그램 목록
  public Object programs(Actor a, long after, int size) {
    return a.manager()
        ? d.page(
            "SELECT p.* FROM programs p WHERE EXISTS(SELECT 1 FROM manager_programs m WHERE"
                + " m.program_id=p.id AND m.manager_id=?)",
            "p.id",
            "id",
            after,
            size,
            a.id())
        : d.page(
            "SELECT p.* FROM programs p JOIN cohorts c ON c.program_id=p.id JOIN scholar_profiles s"
                + " ON s.cohort_id=c.id WHERE s.user_id=?",
            "p.id",
            "id",
            after,
            size,
            a.id());
  }

  // 프로그램 생성
  public Object createProgram(Actor a, String name, String description) {
    manager(a);
    long id = d.id();
    d.update("INSERT INTO programs(id,name,description) VALUES(?,?,?)", id, name, description);
    d.update(
        "INSERT INTO manager_programs(id,manager_id,program_id) VALUES(?,?,?)", d.id(), a.id(), id);
    return Map.of("id", id);
  }

  // 기수 목록
  public Object cohorts(Actor a, long program, long after, int size) {
    if (a.manager())
      return d.page(
          "SELECT c.* FROM cohorts c WHERE c.program_id=? AND EXISTS(SELECT 1 FROM manager_programs"
              + " m WHERE m.manager_id=? AND m.program_id=c.program_id AND (m.cohort_id IS NULL OR"
              + " m.cohort_id=c.id))",
          "c.id",
          "id",
          after,
          size,
          program,
          a.id());
    return d.page(
        "SELECT c.* FROM cohorts c JOIN scholar_profiles s ON s.cohort_id=c.id WHERE s.user_id=?"
            + " AND c.program_id=?",
        "c.id",
        "id",
        after,
        size,
        a.id(),
        program);
  }

  // 기수 생성
  public Object createCohort(Actor a, long program, String name) {
    scope(a, program, null);
    long id = d.id();
    d.update("INSERT INTO cohorts(id,program_id,name) VALUES(?,?,?)", id, program, name);
    return Map.of("id", id);
  }

  // 장학생 목록
  public Object scholars(Actor a, long cohort, long after, int size) {
    var c = d.one("SELECT * FROM cohorts WHERE id=?", cohort);
    scope(a, number(c, "program_id"), cohort);
    return d.page(
        "SELECT u.id,u.email,u.name,u.status AS account_status,s.* FROM users u JOIN"
            + " scholar_profiles s ON s.user_id=u.id WHERE s.cohort_id=? AND u.deleted_at IS NULL",
        "u.id",
        "id",
        after,
        size,
        cohort);
  }

  // 장학생 등록
  public Object createScholar(
      Actor a, long cohort, String email, String name, String password, String school) {
    var c = d.one("SELECT * FROM cohorts WHERE id=?", cohort);
    scope(a, number(c, "program_id"), cohort);
    AuthService.validatePassword(password);
    long id = d.id();
    d.update(
        "INSERT INTO users(id,email,password_hash,name,role,must_change_password,"
            + "temporary_password_expires_at) VALUES(?,?,?,?,'SCHOLAR',TRUE,?)",
        id,
        email.toLowerCase(Locale.ROOT),
        passwords.encode(password),
        name,
        d.after(86400));
    d.update(
        "INSERT INTO scholar_profiles(user_id,cohort_id,school_name) VALUES(?,?,?)",
        id,
        cohort,
        school);
    d.audit(a.id(), "CREATE_SCHOLAR", "USER", id);
    return Map.of("id", id);
  }

  // 활성 양식
  public Object templates() {
    return d.rows("SELECT * FROM request_templates WHERE is_active=TRUE ORDER BY id LIMIT 200");
  }

  // 업무 목록
  public Object requests(Actor a, long after, int size) {
    if (a.manager())
      return d.page(
          "SELECT r.* FROM requests r WHERE EXISTS(SELECT 1 FROM manager_programs m WHERE"
              + " m.manager_id=? AND m.program_id=r.program_id AND (m.cohort_id IS NULL OR"
              + " m.cohort_id=r.cohort_id))",
          "r.id",
          "id",
          after,
          size,
          a.id());
    return d.page(
        "SELECT r.*,s.id AS submission_id,s.status AS submission_status FROM requests r JOIN"
            + " request_targets t ON t.request_id=r.id LEFT JOIN submissions s ON s.request_id=r.id"
            + " AND s.scholar_id=t.scholar_id WHERE t.scholar_id=? AND r.status<>'DRAFT'",
        "r.id",
        "id",
        after,
        size,
        a.id());
  }

  // 입력 항목
  public Object fields(Actor a, long id) {
    request(a, id, false);
    return d.rows("SELECT * FROM request_fields WHERE request_id=? ORDER BY sort_order", id);
  }

  // 업무 생성
  public Object createRequest(
      Actor a,
      long program,
      Long cohort,
      String title,
      String description,
      int year,
      String semester,
      Instant closes,
      String completion) {
    scope(a, program, cohort);
    require(
        Set.of("1", "2", "SUMMER", "WINTER").contains(semester)
            && year >= 2000
            && year <= 2200
            && closes.isAfter(Instant.now()),
        400,
        "INVALID_REQUEST",
        "학기·연도·마감일을 확인해 주세요.");
    if (cohort != null)
      require(
          d.exists("SELECT id FROM cohorts WHERE id=? AND program_id=?", cohort, program),
          400,
          "INVALID_COHORT",
          "프로그램과 기수가 일치하지 않습니다.");
    var templateRow =
        d.one("SELECT * FROM request_templates WHERE code='GRADE_CERTIFICATE' AND is_active=TRUE");
    long template = number(templateRow, "id"), id = d.id();
    d.update(
        "INSERT INTO"
            + " requests(id,program_id,cohort_id,template_id,title,description,academic_year,semester,closes_at,completion_message,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        program,
        cohort,
        template,
        title,
        description,
        year,
        semester,
        Timestamp.from(closes),
        completion,
        a.id());
    @SuppressWarnings("unchecked")
    var defaults =
        (List<Map<String, Object>>)
            ((Map<String, Object>) d.output(templateRow)).get("defaultFields");
    var fields = new ArrayList<Object[]>();
    for (int order = 0; order < defaults.size(); order++) {
      var f = defaults.get(order);
      fields.add(
          new Object[] {
            d.id(),
            id,
            f.get("fieldKey"),
            f.get("label"),
            f.get("fieldType"),
            f.get("required"),
            f.get("options") == null ? null : d.encode(f.get("options")),
            order
          });
    }
    d.batch(
        "INSERT INTO"
            + " request_fields(id,request_id,field_key,label,field_type,`required`,`options`,sort_order)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        fields);
    return Map.of("id", id, "version", 0);
  }

  // 공개 전 업무 수정
  public Object editRequest(
      Actor a, long id, int version, String title, String description, Instant closes) {
    var r = request(a, id, true);
    manager(a);
    version(r, version);
    require("DRAFT".equals(r.get("status")), 409, "INVALID_STATE", "공개 전 업무만 수정할 수 있습니다.");
    require(closes.isAfter(Instant.now()), 400, "INVALID_DEADLINE", "마감일을 확인해 주세요.");
    d.update(
        "UPDATE requests SET title=?,description=?,closes_at=?,version=version+1,updated_at=? WHERE"
            + " id=?",
        title,
        description,
        Timestamp.from(closes),
        d.now(),
        id);
    return request(a, id, false);
  }

  // 업무 공개
  public Object publish(Actor a, long id, int v) {
    var r = request(a, id, true);
    manager(a);
    version(r, v);
    require("DRAFT".equals(r.get("status")), 409, "INVALID_STATE", "이미 공개된 업무입니다.");
    require(
        d.exists("SELECT id FROM requests WHERE id=? AND closes_at>?", id, d.now()),
        409,
        "REQUEST_CLOSED",
        "마감일을 확인해 주세요.");
    var targets =
        d.rows(
            "SELECT s.user_id FROM scholar_profiles s JOIN cohorts c ON c.id=s.cohort_id JOIN users"
                + " u ON u.id=s.user_id WHERE c.program_id=? AND (? IS NULL OR c.id=?) AND"
                + " s.status='ENROLLED' AND u.deleted_at IS NULL",
            r.get("program_id"),
            r.get("cohort_id"),
            r.get("cohort_id"));
    require(!targets.isEmpty(), 409, "NO_TARGETS", "대상 장학생이 없습니다.");
    d.batch(
        "INSERT INTO request_targets(id,request_id,scholar_id) VALUES(?,?,?)",
        targets.stream().map(s -> new Object[] {d.id(), id, s.get("user_id")}).toList());
    d.update(
        "UPDATE requests SET status='IN_PROGRESS',published_at=?,version=version+1,updated_at=?"
            + " WHERE id=?",
        d.now(),
        d.now(),
        id);
    d.audit(a.id(), "PUBLISH_REQUEST", "REQUEST", id);
    return Map.of("targetCount", targets.size(), "version", v + 1);
  }

  // 업무 마감
  public Object close(Actor a, long id, int v) {
    var r = request(a, id, true);
    manager(a);
    version(r, v);
    require("IN_PROGRESS".equals(r.get("status")), 409, "INVALID_STATE", "진행 중 업무만 마감할 수 있습니다.");
    d.update(
        "UPDATE requests SET status='CLOSED',version=version+1,updated_at=? WHERE id=?",
        d.now(),
        id);
    return Map.of("version", v + 1);
  }

  static final String NOT_SUBMITTED = "(s.id IS NULL OR s.status='DRAFT')";

  static String targetSource(boolean notSubmittedOnly) {
    return " FROM request_targets t JOIN users u ON u.id=t.scholar_id"
        + " LEFT JOIN submissions s ON s.request_id=t.request_id AND s.scholar_id=t.scholar_id"
        + " WHERE t.request_id=?" + (notSubmittedOnly ? " AND " + NOT_SUBMITTED : "");
  }

  // 대상자 목록
  public Object targets(Actor a, long id, long after, int size, boolean notSubmittedOnly) {
    manager(a);
    request(a, id, false);
    return d.page(
        "SELECT t.scholar_id,u.name,u.email,s.id AS submission_id,CASE WHEN s.id IS NULL THEN"
            + " 'NOT_STARTED' ELSE CONCAT('',s.status) END AS status,CASE WHEN "
            + NOT_SUBMITTED + " THEN TRUE ELSE FALSE END AS not_submitted"
            + targetSource(notSubmittedOnly),
        "t.scholar_id",
        "scholar_id",
        after,
        size,
        id);
  }

  // 작성 시작
  public Object start(Actor a, long requestId) {
    require(!a.manager(), 403, "FORBIDDEN", "장학생만 작성할 수 있습니다.");
    var r = request(a, requestId, true);
    require(
        "IN_PROGRESS".equals(r.get("status"))
            && d.exists("SELECT id FROM requests WHERE id=? AND closes_at>?", requestId, d.now()),
        409,
        "REQUEST_CLOSED",
        "마감된 업무입니다.");
    var existing =
        d.rows("SELECT * FROM submissions WHERE request_id=? AND scholar_id=?", requestId, a.id());
    if (!existing.isEmpty()) return existing.getFirst();
    long id = d.id();
    d.update(
        "INSERT INTO submissions(id,request_id,scholar_id,answers) VALUES(?,?,?,?)",
        id,
        requestId,
        a.id(),
        "{}");
    d.update(
        "INSERT INTO submission_histories(id,submission_id,to_status,actor_type,actor_id,snapshot)"
            + " VALUES(?,?,'DRAFT','SCHOLAR',?,?)",
        d.id(),
        id,
        a.id(),
        "{}");
    return submission(a, id, false);
  }

  // 변경 전 자료 기록
  public void history(Actor a, Map<String, Object> s, String to) {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("submission", d.output(s));
    snapshot.put(
        "files",
        d.output(
            d.rows(
                "SELECT * FROM submission_files WHERE submission_id=? AND upload_status='UPLOADED'"
                    + " AND superseded_at IS NULL",
                s.get("id"))));
    d.update(
        "INSERT INTO"
            + " submission_histories(id,submission_id,from_status,to_status,actor_type,actor_id,snapshot)"
            + " VALUES(?,?,?,?,?,?,?)",
        d.id(),
        s.get("id"),
        s.get("status"),
        to,
        a.role(),
        a.id(),
        d.encode(snapshot));
  }

  // 보완 대상 항목
  public Set<String> revisionFields(long id) {
    var result = new HashSet<String>();
    for (var f :
        d.rows(
            "SELECT f.field_key FROM revision_request_fields f JOIN revision_requests r ON"
                + " r.id=f.revision_request_id WHERE r.submission_id=? AND r.resolved_at IS NULL",
            id)) result.add(text(f, "field_key"));
    return result;
  }

  // 이전 분석 무효화
  public void invalidate(long id) {
    d.update(
        "UPDATE document_analysis_results SET invalidated_at=? WHERE submission_id=? AND"
            + " invalidated_at IS NULL",
        d.now(),
        id);
  }

  // 성적 숫자 변환
  private BigDecimal decimal(Object v) {
    try {
      require(v instanceof Number, 400, "INVALID_ANSWERS", "성적은 숫자로 입력해 주세요.");
      return new BigDecimal(v.toString());
    } catch (NumberFormatException e) {
      throw new ApiException(400, "INVALID_ANSWERS", "성적은 숫자로 입력해 주세요.");
    }
  }

  // 성적 입력 검증
  public void validateAnswers(Map<String, Object> answers, boolean complete) {
    require(
        answers != null && ANSWER_KEYS.containsAll(answers.keySet()),
        400,
        "INVALID_ANSWERS",
        "허용되지 않은 항목입니다.");
    if (complete)
      require(answers.keySet().containsAll(ANSWER_KEYS), 400, "REQUIRED_FIELDS", "필수 항목을 입력해 주세요.");
    for (var e : answers.entrySet()) {
      var n = decimal(e.getValue());
      require(
          n.signum() >= 0
              && n.compareTo(new BigDecimal(e.getKey().equals("credits") ? "100" : "4.5")) <= 0
              && n.scale() <= 2,
          400,
          "INVALID_ANSWERS",
          "성적 입력 범위를 확인해 주세요.");
      if (e.getKey().equals("gpa_scale"))
        require(
            Set.of("4", "4.3", "4.5").contains(n.stripTrailingZeros().toPlainString()),
            400,
            "INVALID_ANSWERS",
            "허용된 만점을 선택해 주세요.");
    }
    if (answers.containsKey("gpa") && answers.containsKey("gpa_scale"))
      require(
          decimal(answers.get("gpa")).compareTo(decimal(answers.get("gpa_scale"))) <= 0,
          400,
          "INVALID_ANSWERS",
          "평점이 만점보다 높습니다.");
  }

  // 성적 임시 저장
  public Object save(Actor a, long id, int v, Map<String, Object> answers) {
    var s = submission(a, id, true);
    writable(a, s);
    version(s, v);
    validateAnswers(answers, false);
    if ("REVISION_REQUIRED".equals(s.get("status"))) {
      var old = d.object(s.get("answers"));
      var allowed = revisionFields(id);
      for (String key : ANSWER_KEYS)
        if (!allowed.contains(key))
          require(
              Objects.equals(
                  old.get(key) == null ? null : decimal(old.get(key)).stripTrailingZeros(),
                  answers.get(key) == null ? null : decimal(answers.get(key)).stripTrailingZeros()),
              400,
              "FIELD_LOCKED",
              "보완 요청된 항목만 수정할 수 있습니다.");
    }
    history(a, s, text(s, "status"));
    invalidate(id);
    d.update(
        "UPDATE submissions SET answers=?,version=version+1,updated_at=? WHERE id=?",
        d.encode(answers),
        d.now(),
        id);
    return submission(a, id, false);
  }

  // 최종 제출
  public Object submit(
      Actor a, long id, int v, boolean acknowledged, boolean manual, String reason) {
    var s = submission(a, id, true);
    writable(a, s);
    version(s, v);
    validateAnswers(d.object(s.get("answers")), true);
    require(acknowledged, 400, "NOTICE_REQUIRED", "문서 처리 안내를 확인해 주세요.");
    require(
        d.exists(
            "SELECT id FROM submission_files WHERE submission_id=? AND upload_status='UPLOADED' AND"
                + " superseded_at IS NULL",
            id),
        400,
        "FILE_REQUIRED",
        "증명서를 첨부해 주세요.");
    boolean match =
        d.exists(
            "SELECT id FROM document_analysis_results WHERE submission_id=? AND invalidated_at IS"
                + " NULL AND status='COMPLETED' AND overall_result='MATCH'",
            id);
    require(match || manual, 409, "MANUAL_REVIEW_REQUIRED", "유효한 일치 결과가 없으므로 수동 확인을 요청해 주세요.");
    require(
        !manual || (reason != null && !reason.isBlank() && reason.length() <= 2000),
        400,
        "REASON_REQUIRED",
        "수동 확인 사유를 입력해 주세요.");
    String next = "DRAFT".equals(s.get("status")) ? "SUBMITTED" : "RESUBMITTED";
    history(a, s, next);
    d.update(
        "UPDATE submissions SET"
            + " status=?,submit_count=submit_count+1,manual_review_requested=?,manual_review_reason=?,notice_acknowledged_at=?,submitted_at=COALESCE(submitted_at,?),version=version+1,updated_at=?"
            + " WHERE id=?",
        next,
        manual,
        manual ? reason : null,
        d.now(),
        d.now(),
        d.now(),
        id);
    if (d.exists(
        "SELECT id FROM requests WHERE id=? AND closes_at<=?", s.get("request_id"), d.now()))
      d.update("UPDATE submissions SET is_late=TRUE WHERE id=?", id);
    if (next.equals("RESUBMITTED"))
      d.update(
          "UPDATE revision_requests SET resolved_at=? WHERE submission_id=? AND resolved_at IS"
              + " NULL",
          d.now(),
          id);
    d.audit(a.id(), next, "SUBMISSION", id);
    return submission(a, id, false);
  }

  // 담당자 검토
  public Object review(
      Actor a, long id, int v, boolean confirm, String reason, List<String> fields) {
    manager(a);
    var s = submission(a, id, true);
    version(s, v);
    require(
        Set.of("SUBMITTED", "RESUBMITTED").contains(text(s, "status")),
        409,
        "INVALID_STATE",
        "검토 대기 상태만 처리할 수 있습니다.");
    String next = confirm ? "CONFIRMED" : "REVISION_REQUIRED";
    if (!confirm) {
      require(
          reason != null
              && !reason.isBlank()
              && reason.length() <= 2000
              && fields != null
              && !fields.isEmpty()
              && new HashSet<>(fields).size() == fields.size()
              && FIELD_KEYS.containsAll(fields),
          400,
          "INVALID_REVISION",
          "보완 사유와 대상 항목을 확인해 주세요.");
      long revision = d.id();
      d.update(
          "INSERT INTO revision_requests(id,submission_id,reason,requested_by) VALUES(?,?,?,?)",
          revision,
          id,
          reason,
          a.id());
      d.batch(
          "INSERT INTO revision_request_fields(id,revision_request_id,field_key) VALUES(?,?,?)",
          fields.stream().map(field -> new Object[] {d.id(), revision, field}).toList());
    }
    history(a, s, next);
    d.update(
        "UPDATE submissions SET"
            + " status=?,confirmed_at=?,confirmed_by=?,version=version+1,updated_at=? WHERE id=?",
        next,
        confirm ? d.now() : null,
        confirm ? a.id() : null,
        d.now(),
        id);
    d.audit(a.id(), next, "SUBMISSION", id);
    return submission(a, id, false);
  }

  // 변경 이력
  public Object histories(Actor a, long id) {
    submission(a, id, false);
    return d.rows(
        "SELECT * FROM submission_histories WHERE submission_id=? ORDER BY changed_at,id", id);
  }

  // 보완 이력
  public Object revisions(Actor a, long id) {
    submission(a, id, false);
    var rows =
        d.rows(
            "SELECT * FROM revision_requests WHERE submission_id=? ORDER BY requested_at,id", id);
    if (rows.isEmpty()) return rows;
    var byRevision = new HashMap<Long, List<Map<String, Object>>>();
    for (var field :
        d.rows(
            "SELECT f.revision_request_id,f.field_key FROM revision_request_fields f JOIN"
                + " revision_requests r ON r.id=f.revision_request_id WHERE r.submission_id=? ORDER"
                + " BY f.id",
            id)) {
      byRevision
          .computeIfAbsent(number(field, "revision_request_id"), key -> new ArrayList<>())
          .add(Map.of("field_key", field.get("field_key")));
    }
    for (var row : rows) row.put("fields", byRevision.getOrDefault(number(row, "id"), List.of()));
    return rows;
  }

  // 제출 목록
  public Object submissions(Actor a, long request, long after, int size) {
    manager(a);
    request(a, request, false);
    return d.page(
        "SELECT s.*,u.name,u.email FROM submissions s JOIN users u ON u.id=s.scholar_id WHERE"
            + " s.request_id=?",
        "s.id",
        "id",
        after,
        size,
        request);
  }
}
