package kr.co.ktscholarship.backend.api;

import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
// 마감일 기준 파기
public class RetentionService {
  // DB 접근
  private final Database d;
  // 파일 삭제 작업
  private final FileDeletionJobs deletion;
  // 파기 트랜잭션
  private final TransactionTemplate tx;
  // 자동 처리
  private final boolean enabled;
  // 감사 보관 일수
  private final int auditDays;
  // 파기 오류
  private final io.micrometer.core.instrument.Counter failures;

  // 파기 설정
  public RetentionService(Database d,FileDeletionJobs deletion,PlatformTransactionManager manager,MeterRegistry metrics,@Value("${app.maintenance.enabled:true}")boolean enabled,@Value("${app.audit.retention-days:180}")int auditDays){
    if(auditDays<1||auditDays>365)throw new IllegalArgumentException("INVALID_AUDIT_RETENTION");
    this.d=d;this.deletion=deletion;this.enabled=enabled;this.auditDays=auditDays;tx=new TransactionTemplate(manager);tx.setTimeout(30);failures=metrics.counter("retention.failures");
  }

  // 보관 만료 검사
  public static boolean expired(Map<String,Object> request){
    Object value=request.get("closes_at");LocalDateTime deadline=value instanceof LocalDateTime date?date:((java.sql.Timestamp)value).toLocalDateTime();
    return request.get("purged_at")!=null||!deadline.plusYears(1).isAfter(LocalDateTime.now(ZoneOffset.UTC));
  }

  // 요청 단위 파기
  public boolean purge(long id){
    return Boolean.TRUE.equals(tx.execute(status->{
      var request=d.one("SELECT * FROM requests WHERE id=? FOR UPDATE",id);
      if(request.get("purged_at")!=null||!expired(request))return false;
      d.rows("SELECT id FROM submissions WHERE request_id=? ORDER BY id FOR UPDATE",id);
      var files=d.rows("SELECT f.id,f.s3_key FROM submission_files f JOIN submissions s ON s.id=f.submission_id WHERE s.request_id=?",id);
      for(var file:files)deletion.enqueue(Database.text(file,"s3_key"),Database.number(file,"id"));
      d.update("DELETE FROM revision_request_fields WHERE revision_request_id IN (SELECT r.id FROM revision_requests r JOIN submissions s ON s.id=r.submission_id WHERE s.request_id=?)",id);
      for(String table:List.of("document_analysis_results","revision_requests","submission_histories","submission_files"))d.update("DELETE FROM "+table+" WHERE submission_id IN (SELECT id FROM submissions WHERE request_id=?)",id);
      d.update("DELETE FROM submissions WHERE request_id=?",id);d.update("DELETE FROM request_targets WHERE request_id=?",id);d.update("DELETE FROM request_fields WHERE request_id=?",id);
      d.update("UPDATE requests SET title='보관 기간 만료 업무',description=NULL,completion_message=NULL,purged_at=?,version=version+1 WHERE id=?",d.now(),id);
      d.update("INSERT INTO audit_logs(id,action,target_type,target_id,row_count) VALUES(?,'PURGE_REQUEST','REQUEST',?,?)",d.id(),id,files.size());return true;
    }));
  }

  // 만료 자료 처리
  public int purgeBatch(){
    int count=0;LocalDateTime candidate=LocalDateTime.now(ZoneOffset.UTC).minusYears(1).plusDays(1);
    for(var request:d.rows("SELECT id FROM requests WHERE purged_at IS NULL AND closes_at<=? ORDER BY closes_at,id LIMIT 100",candidate)){
      try{if(purge(Database.number(request,"id")))count++;}catch(RuntimeException e){failures.increment();org.slf4j.LoggerFactory.getLogger(RetentionService.class).error("retention_request_failed requestId={}",request.get("id"));}
    }
    return count;
  }

  // 자동 정리
  @Scheduled(fixedDelayString="${app.maintenance.interval-ms:300000}",initialDelayString="${app.maintenance.initial-delay-ms:10000}")
  public void scheduled(){
    if(!enabled)return;
    try{purgeBatch();deletion.reconcile();deletion.runBatch();cleanup();}
    catch(Exception e){failures.increment();org.slf4j.LoggerFactory.getLogger(RetentionService.class).error("maintenance_failed");}
  }

  // 만료 인증 및 감사 자료
  public void cleanup(){
    tx.executeWithoutResult(status->{
      d.update("DELETE FROM access_tokens WHERE expires_at<?",d.now());d.update("DELETE FROM refresh_tokens WHERE expires_at<?",d.now());
      d.update("DELETE FROM auth_challenges WHERE expires_at<?",d.now());
      d.update("DELETE FROM auth_sessions WHERE absolute_expires_at<? AND id NOT IN (SELECT session_id FROM access_tokens WHERE session_id IS NOT NULL) AND id NOT IN (SELECT session_id FROM refresh_tokens WHERE session_id IS NOT NULL)",d.now());
      d.update("DELETE FROM audit_logs WHERE created_at<?",d.after(-86400L*auditDays));
      d.update("DELETE FROM request_budgets WHERE window_start<?",System.currentTimeMillis()/60000-60);
      d.update("DELETE FROM file_deletion_jobs WHERE completed_at<?",d.after(-86400L*7));
    });
  }
}
