package kr.co.ktscholarship.backend.api;

import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Service
// 영속 파일 삭제 작업
public class FileDeletionJobs {
  // DB 접근
  private final Database d;
  // 저장소
  private final FileStorage storage;
  // 작업 트랜잭션
  private final TransactionTemplate tx;
  // 오류 집계
  private final io.micrometer.core.instrument.Counter failures;

  // 작업 설정
  public FileDeletionJobs(Database d,FileStorage storage,PlatformTransactionManager manager,MeterRegistry metrics) {
    this.d=d;this.storage=storage;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(15);
    failures=metrics.counter("storage.deletion.failures");
    metrics.gauge("storage.deletion.pending",this,jobs->Database.number(d.one("SELECT COUNT(*) AS pending FROM file_deletion_jobs WHERE completed_at IS NULL"),"pending"));
  }

  // 삭제 예약
  public void enqueue(String key,Long file) {
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("DELETION_REQUIRES_TRANSACTION");
    storage.path(key);
    d.update("INSERT INTO file_deletion_jobs(id,storage_key,file_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE storage_key=VALUES(storage_key)",d.id(),key,file);
  }

  // 삭제 재시도
  public void process(long id) {
    tx.executeWithoutResult(status->{
      var rows=d.rows("SELECT * FROM file_deletion_jobs WHERE id=? AND completed_at IS NULL AND next_attempt_at<=? FOR UPDATE",id,d.now());
      if(rows.isEmpty())return;var job=rows.getFirst();
      try {
        storage.remove(Database.text(job,"storage_key"));
        d.update("UPDATE file_deletion_jobs SET completed_at=?,last_error=NULL WHERE id=?",d.now(),id);
      }catch(Exception e){
        failures.increment();long attempts=Database.number(job,"attempt_count")+1;
        d.update("UPDATE file_deletion_jobs SET attempt_count=?,next_attempt_at=?,last_error=? WHERE id=?",attempts,d.after(Math.min(3600,5L<<(int)Math.min(attempts,9))),"DELETE_FAILED",id);
        org.slf4j.LoggerFactory.getLogger(FileDeletionJobs.class).error("file_deletion_retry jobId={}",id);
      }
    });
  }

  // 대기 작업 처리
  public void runBatch() {
    for(var job:d.rows("SELECT id FROM file_deletion_jobs WHERE completed_at IS NULL AND next_attempt_at<=? ORDER BY requested_at,id LIMIT 100",d.now())) {
      try{process(Database.number(job,"id"));}catch(RuntimeException e){failures.increment();org.slf4j.LoggerFactory.getLogger(FileDeletionJobs.class).error("file_deletion_transaction_failed jobId={}",job.get("id"));}
    }
  }

  // 고아 파일 정리
  public void reconcile() throws java.io.IOException {
    for(String key:storage.orphanCandidates())tx.executeWithoutResult(status->{
      if(!d.exists("SELECT id FROM submission_files WHERE s3_key=?",key))enqueue(key,null);
    });
  }
}
