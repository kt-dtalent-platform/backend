package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.ApiException.require;
import static kr.co.ktscholarship.backend.api.Database.*;
import java.io.*;
import java.util.*;
import kr.co.ktscholarship.backend.api.AuthService.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
// 파일 관리
public class FileService {
  // DB 접근
  private final Database d;
  // 제출 업무
  private final WorkflowService w;
  // 내용 검증
  private final FilePolicy policy;
  // 비공개 저장소
  private final FileStorage storage;
  // 삭제 작업
  private final FileDeletionJobs deletion;
  // 저장 트랜잭션
  private final TransactionTemplate tx;

  // 의존성 주입
  public FileService(Database d,WorkflowService w,FilePolicy policy,FileStorage storage,FileDeletionJobs deletion,PlatformTransactionManager manager) {
    this.d=d;this.w=w;this.policy=policy;this.storage=storage;this.deletion=deletion;
    tx=new TransactionTemplate(manager);tx.setTimeout(15);
  }

  // 파일 접근 검사
  public Map<String,Object> file(Actor a,long id) {
    var file=d.one("SELECT * FROM submission_files WHERE id=?",id);w.submission(a,number(file,"submission_id"),false);return file;
  }

  // 파일 목록
  public Object list(Actor a,long id) {
    w.submission(a,id,false);
    return d.rows("SELECT f.*,j.completed_at AS deletion_completed_at FROM submission_files f LEFT JOIN file_deletion_jobs j ON j.file_id=f.id WHERE f.submission_id=? ORDER BY f.created_at,f.id",id);
  }

  // 원본 무결성 검증
  public byte[] bytes(Map<String,Object> file) throws IOException {return storage.read(file);}

  // 파일 업로드
  public Object upload(Actor a,long id,int version,MultipartFile upload) throws IOException {
    var current = w.submission(a,id,false);
    require(!a.manager(),403,"FORBIDDEN","장학생 본인만 수정할 수 있습니다.");
    w.version(current,version);
    require(!upload.isEmpty()&&upload.getSize()<=FilePolicy.MAX_BYTES,400,"INVALID_FILE","파일 크기를 확인해 주세요.");
    String original=Objects.toString(upload.getOriginalFilename(),"").replace('\\','/');original=original.substring(original.lastIndexOf('/')+1);
    require(!original.isBlank()&&original.length()<=200&&original.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT),400,"INVALID_FILENAME","파일명을 확인해 주세요.");
    byte[] data=upload.getBytes();var verified=policy.validate(data,original);
    var stored=storage.write(data);String name=original;
    try {
      return tx.execute(status->{
        var submission=w.submission(a,id,true);w.writable(a,submission);w.version(submission,version);
        if("REVISION_REQUIRED".equals(submission.get("status")))require(w.revisionFields(id).contains("certificate"),400,"FIELD_LOCKED","파일은 보완 대상이 아닙니다.");
        var quota=d.one("SELECT COUNT(*) AS file_count,COALESCE(SUM(size_bytes),0) AS total_bytes FROM submission_files WHERE submission_id=?",id);
        require(number(quota,"file_count")<20&&number(quota,"total_bytes")+data.length<=50L*1024*1024,413,"STORAGE_LIMIT","제출당 누적 업로드는 최대 20개·50MiB입니다.");
        w.history(a,submission,text(submission,"status"));w.invalidate(id);
        d.update("UPDATE submission_files SET superseded_at=? WHERE submission_id=? AND superseded_at IS NULL AND upload_status='UPLOADED'",d.now(),id);
        long file=d.id();
        d.update("INSERT INTO submission_files(id,submission_id,field_key,s3_key,original_name,content_type,size_bytes,checksum,upload_status,uploaded_at,storage_format,scan_status) VALUES(?,?,'certificate',?,?,?,?,?,'UPLOADED',?,?,?)",file,id,stored.key(),name,verified.contentType(),data.length,FileStorage.checksum(data),d.now(),stored.format(),verified.scanStatus());
        d.update("UPDATE submissions SET version=version+1,updated_at=? WHERE id=?",d.now(),id);
        return Map.of("id",file,"version",version+1,"contentType",verified.contentType(),"sizeBytes",data.length,"scanStatus",verified.scanStatus());
      });
    }catch(RuntimeException e){
      try{storage.remove(stored.key());}catch(IOException cleanup){org.slf4j.LoggerFactory.getLogger(FileService.class).error("orphan_file_cleanup_pending");}
      throw e;
    }
  }

  // 논리 삭제 및 재시도 예약
  public Object delete(Actor a,long file,int version) {
    return tx.execute(status->{
      var metadata=d.one("SELECT submission_id,s3_key,upload_status FROM submission_files WHERE id=?",file);
      long id=number(metadata,"submission_id");var submission=w.submission(a,id,true);w.writable(a,submission);w.version(submission,version);
      require(number(submission,"submit_count")==0,409,"FILE_PRESERVED","제출에 사용된 파일은 보존해야 합니다.");
      require("UPLOADED".equals(metadata.get("upload_status")),409,"INVALID_STATE","이미 삭제된 파일입니다.");
      w.history(a,submission,text(submission,"status"));w.invalidate(id);
      d.update("UPDATE submission_files SET upload_status='DELETED' WHERE id=?",file);
      deletion.enqueue(text(metadata,"s3_key"),file);
      d.update("UPDATE submissions SET version=version+1,updated_at=? WHERE id=?",d.now(),id);
      d.audit(a.id(),"DELETE_FILE","FILE",file);
      return Map.of("version",version+1,"deletionStatus","QUEUED");
    });
  }

  // 다운로드 자료
  public record Download(Map<String,Object> file,byte[] content) {}

  // 인증 원본 다운로드
  public Download download(Actor a,long id) throws IOException {
    var file=file(a,id);byte[] bytes=bytes(file);d.audit(a.id(),"DOWNLOAD_FILE","FILE",id);return new Download(file,bytes);
  }
}
