package kr.co.ktscholarship.backend.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionExportRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    public SubmissionExportRepository(JdbcClient jdbc,JsonMapper json) { this.jdbc=jdbc;this.json=json; }
    boolean exists(long request) {
        return jdbc.sql("SELECT COUNT(*) FROM requests WHERE id=:id").param("id",request).query(Long.class).single()>0;
    }
    List<Field> fields(long request) {
        return jdbc.sql("SELECT field_key,label,field_type FROM request_fields WHERE request_id=:id ORDER BY sort_order,id")
            .param("id",request).query((r,n)->new Field(r.getString(1),r.getString(2),r.getString(3))).list();
    }
    List<Row> rows(long request) {
        return jdbc.sql("""
            SELECT u.name,u.email,c.name cohort,p.school_name,p.major,p.student_no,p.phone,
            s.id,s.status,s.answers,s.submitted_at
            FROM request_targets t JOIN users u ON u.id=t.scholar_id
            LEFT JOIN scholar_profiles p ON p.user_id=u.id LEFT JOIN cohorts c ON c.id=p.cohort_id
            LEFT JOIN submissions s ON s.request_id=t.request_id AND s.scholar_id=t.scholar_id
            WHERE t.request_id=:id ORDER BY u.name,u.id
            """).param("id",request).query((r,n)->new Row(r.getObject("id",Long.class),r.getString("name"),r.getString("cohort"),
                r.getString("email"),r.getString("school_name"),r.getString("major"),r.getString("student_no"),r.getString("phone"),
                r.getString("status"),UtcDateTimes.fromDb(r.getObject("submitted_at",LocalDateTime.class)),
                r.getString("answers")==null?Map.of():json.readValue(r.getString("answers"),new TypeReference<Map<String,Object>>() {}))).list();
    }
    List<Attachment> files(long request) {
        return jdbc.sql("""
            SELECT f.submission_id,f.field_key,f.original_name FROM submission_files f
            JOIN submissions s ON s.id=f.submission_id WHERE s.request_id=:id AND f.upload_status='UPLOADED' ORDER BY f.id
            """).param("id",request).query((r,n)->new Attachment(r.getLong(1),r.getString(2),r.getString(3))).list();
    }
    List<Revision> revisions(long request) {
        return jdbc.sql("""
            SELECT rr.submission_id,rr.reason FROM revision_requests rr JOIN submissions s ON s.id=rr.submission_id
            WHERE s.request_id=:id AND rr.resolved_at IS NULL ORDER BY rr.requested_at DESC,rr.id DESC
            """).param("id",request).query((r,n)->new Revision(r.getLong(1),r.getString(2))).list();
    }
    void audit(long actor,long request,int count,String ip,Instant now) {
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id,action,target_type,target_id,row_count,ip_address,created_at)
            VALUES(:actor,'EXPORT_SUBMISSIONS','REQUEST',:request,:count,:ip,:now)
            """).param("actor",actor).param("request",request).param("count",count).param("ip",ip,java.sql.Types.VARCHAR)
            .param("now",UtcDateTimes.toDb(now)).update();
    }
    record Field(String key,String label,String type) {}
    record Row(Long submissionId,String name,String cohort,String email,String university,String major,String studentNo,String phone,
        String status,Instant submittedAt,Map<String,Object> answers) {}
    record Attachment(long submissionId,String field,String name) {}
    record Revision(long submissionId,String reason) {}
}
