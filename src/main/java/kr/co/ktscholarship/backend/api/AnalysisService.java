package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.ApiException.require;
import static kr.co.ktscholarship.backend.api.Database.*;
import java.math.BigDecimal;
import java.util.*;
import kr.co.ktscholarship.backend.api.AuthService.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
// 문서 분석
public class AnalysisService {
  // DB 접근
  private final Database d;
  // 제출 업무
  private final WorkflowService w;
  // 파일 접근
  private final FileService files;
  // 외부 분석
  private final OcrClient ocr;
  // 짧은 상태 트랜잭션
  private final TransactionTemplate tx;

  // 의존성 주입
  public AnalysisService(Database d,WorkflowService w,FileService files,OcrClient ocr,PlatformTransactionManager manager){this.d=d;this.w=w;this.files=files;this.ocr=ocr;tx=new TransactionTemplate(manager);tx.setTimeout(15);}

  // 분석 입력 스냅샷
  private record Job(long id,long submission,int version,Map<String,Object> file,Map<String,Object> answers,int year,String semester,String school){}

  // 분석 예약
  private Job prepare(Actor actor,long submission,long file,int version,boolean acknowledged){
    var current=w.submission(actor,submission,true);w.writable(actor,current);w.version(current,version);
    require(acknowledged,400,"NOTICE_REQUIRED","문서 처리 및 외부 분석 안내를 확인해 주세요.");
    var metadata=files.file(actor,file);
    require(number(metadata,"submission_id")==submission&&metadata.get("superseded_at")==null&&"UPLOADED".equals(metadata.get("upload_status")),400,"INVALID_FILE","현재 제출 파일이 아닙니다.");
    var answers=d.object(current.get("answers"));w.validateAnswers(answers,true);
    d.update("UPDATE document_analysis_results SET status='FAILED',failure_reason='WORKER_INTERRUPTED',invalidated_at=?,completed_at=? WHERE submission_id=? AND status='REQUESTED' AND requested_at<?",d.now(),d.now(),submission,d.after(-90));
    require(!d.exists("SELECT id FROM document_analysis_results WHERE submission_id=? AND status='REQUESTED' AND invalidated_at IS NULL",submission),409,"ANALYSIS_IN_PROGRESS","이미 분석 중입니다.");
    int attempt=(int)number(d.one("SELECT COUNT(*) AS attempts FROM document_analysis_results WHERE file_id=?",file),"attempts")+1;
    require(attempt<=5,409,"ANALYSIS_LIMIT","파일당 분석은 5회까지 가능합니다.");
    w.invalidate(submission);long id=d.id();
    d.update("INSERT INTO document_analysis_results(id,submission_id,file_id,attempt_number,entered_values,requested_by) VALUES(?,?,?,?,?,?)",id,submission,file,attempt,d.encode(answers),actor.id());
    d.update("UPDATE submissions SET notice_acknowledged_at=? WHERE id=?",d.now(),submission);
    var request=w.request(actor,number(current,"request_id"),false);
    String school=text(d.one("SELECT school_name FROM scholar_profiles WHERE user_id=?",actor.id()),"school_name");
    return new Job(id,submission,version,metadata,answers,((Number)request.get("academic_year")).intValue(),text(request,"semester"),school);
  }

  // 외부 호출 분리
  public Object analyze(Actor actor,long submission,long file,int version,boolean acknowledged){
    Job job=tx.execute(status->prepare(actor,submission,file,version,acknowledged));
    Map<String,Object> extracted=null;String failure=null;
    try {extracted=ocr.extract(files.bytes(job.file()),text(job.file(),"content_type"));}
    catch(Exception error){
      if(error instanceof InterruptedException)Thread.currentThread().interrupt();
      failure=error instanceof IllegalStateException&&"OCR_NOT_CONFIGURED".equals(error.getMessage())?"OCR_NOT_CONFIGURED":error instanceof ApiException api?api.code:"OCR_UNAVAILABLE";
    }
    Map<String,Object> result=extracted;String reason=failure;
    tx.executeWithoutResult(status->finish(actor,job,result,reason));
    return result(actor,job.id());
  }

  // 결과 버전 검사
  private void finish(Actor actor,Job job,Map<String,Object> extracted,String failure){
    var current=w.submission(actor,job.submission(),true);
    var analysis=d.one("SELECT * FROM document_analysis_results WHERE id=? FOR UPDATE",job.id());
    boolean stale=number(current,"version")!=job.version()||analysis.get("invalidated_at")!=null||!d.exists("SELECT id FROM submission_files WHERE id=? AND superseded_at IS NULL AND upload_status='UPLOADED'",job.file().get("id"));
    if(stale)failure="INPUT_CHANGED";
    if(failure!=null){
      d.update("UPDATE document_analysis_results SET status='FAILED',overall_result='UNREADABLE',failure_reason=?,completed_at=?,invalidated_at=? WHERE id=?",failure,d.now(),stale?d.now():analysis.get("invalidated_at"),job.id());
    }else{
      var safe=new LinkedHashMap<String,Object>();
      for(String field:List.of("school","documentType","academicYear","semester","gpa","gpa_scale","credits","hasF"))if(extracted.containsKey(field))safe.put(field,extracted.get(field));
      var verdict=compare(job.answers(),safe,job.year(),job.semester(),job.school());
      d.update("UPDATE document_analysis_results SET status='COMPLETED',extracted_values=?,comparisons=?,overall_result=?,analyzer_version='external-v1',completed_at=? WHERE id=?",d.encode(safe),d.encode(verdict),verdict.get("overallResult"),d.now(),job.id());
    }
    d.audit(actor.id(),"ANALYZE_DOCUMENT","SUBMISSION",job.submission());
  }

  // 성적 비교
  public static Map<String, Object> compare(
      Map<String, Object> entered,
      Map<String, Object> extracted,
      int year,
      String semester,
      String expectedSchool) {
    Map<String, String> comparisons = new LinkedHashMap<>();
    boolean unreadable = false, mismatch = false;
    for (String key : List.of("gpa", "gpa_scale", "credits")) {
      String result;
      try {
        Object raw = extracted.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException();
        var n = new BigDecimal(raw.toString());
        if (n.signum() < 0
            || n.compareTo(new BigDecimal(key.equals("credits") ? "100" : "4.5")) > 0)
          throw new IllegalArgumentException();
        result =
            new BigDecimal(entered.get(key).toString()).compareTo(n) == 0 ? "MATCH" : "MISMATCH";
      } catch (Exception e) {
        result = "UNREADABLE";
      }
      comparisons.put(key, result);
      unreadable |= result.equals("UNREADABLE");
      mismatch |= result.equals("MISMATCH");
    }
    boolean fOrUnknown = !Boolean.FALSE.equals(extracted.get("hasF"));
    boolean documentUnknown =
        !(extracted.get("school") instanceof String school)
            || school.isBlank()
            || !"GRADE_CERTIFICATE".equals(extracted.get("documentType"));
    boolean periodUnknown =
        !(extracted.get("academicYear") instanceof Number) || extracted.get("semester") == null;
    boolean periodMismatch =
        !periodUnknown
            && (new BigDecimal(extracted.get("academicYear").toString())
                        .compareTo(BigDecimal.valueOf(year))
                    != 0
                || !semester.equals(extracted.get("semester").toString()));
    boolean schoolMismatch =
        !documentUnknown
            && !expectedSchool
                .replaceAll("\\s+", "")
                .equalsIgnoreCase(extracted.get("school").toString().replaceAll("\\s+", ""));
    String result =
        unreadable || fOrUnknown || documentUnknown || periodUnknown
            ? "UNREADABLE"
            : mismatch || periodMismatch || schoolMismatch ? "MISMATCH" : "MATCH";
    return Map.of(
        "overallResult",
        result,
        "fields",
        comparisons,
        "manualReviewRequired",
        !result.equals("MATCH"),
        "fDetectedOrUnknown",
        fOrUnknown,
        "periodMismatch",
        periodMismatch,
        "schoolMismatch",
        schoolMismatch);
  }

  // 분석 결과
  public Object result(Actor a, long id) {
    var r = d.one("SELECT * FROM document_analysis_results WHERE id=?", id);
    w.submission(a, number(r, "submission_id"), false);
    return r;
  }

  // 목록 조회
  public Object list(Actor a, long id) {
    w.submission(a, id, false);
    return d.rows(
        "SELECT * FROM document_analysis_results WHERE submission_id=? ORDER BY requested_at,id",
        id);
  }
}
