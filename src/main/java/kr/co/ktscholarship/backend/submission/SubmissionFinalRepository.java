package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionFinalRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String jsonParameter;
    public SubmissionFinalRepository(JdbcClient jdbc,JsonMapper json,DataSource source) throws MetaDataAccessException {
        this.jdbc=jdbc; this.json=json;
        String database=JdbcUtils.extractDatabaseMetaData(source,java.sql.DatabaseMetaData::getDatabaseProductName);
        jsonParameter=database.equals("H2")?":json FORMAT JSON":"CAST(:json AS JSON)";
    }

    Optional<Submission> lock(long request,long scholar) {
        return jdbc.sql("SELECT id,status,version,answers,submitted_at FROM submissions WHERE request_id=:request AND scholar_id=:scholar FOR UPDATE")
            .param("request",request).param("scholar",scholar).query((r,n)->new Submission(r.getLong("id"),r.getString("status"),r.getInt("version"),
                json.readValue(r.getString("answers"),new TypeReference<Map<String,Object>>() {}),
                UtcDateTimes.fromDb(r.getObject("submitted_at",LocalDateTime.class)))).optional();
    }

    Optional<Request> request(long id) {
        return jdbc.sql("SELECT status,closes_at,completion_message FROM requests WHERE id=:id AND published_at IS NOT NULL AND status<>'DRAFT'")
            .param("id",id).query((r,n)->new Request(r.getString("status"),UtcDateTimes.fromDb(r.getObject("closes_at",LocalDateTime.class)),r.getString("completion_message"))).optional();
    }

    Optional<Revision> revision(long submission) {
        return jdbc.sql("SELECT id FROM revision_requests WHERE submission_id=:id AND resolved_at IS NULL ORDER BY requested_at DESC,id DESC LIMIT 1 FOR UPDATE")
            .param("id",submission).query(Long.class).optional().map(id->new Revision(id,new HashSet<>(jdbc.sql(
                "SELECT field_key FROM revision_request_fields WHERE revision_request_id=:id").param("id",id).query(String.class).list())));
    }

    List<FileSnapshot> files(long submission) {
        return jdbc.sql("SELECT id,field_key,original_name,size_bytes,upload_status FROM submission_files WHERE submission_id=:id AND upload_status<>'DELETED' ORDER BY id")
            .param("id",submission).query((r,n)->new FileSnapshot(r.getLong("id"),r.getString("field_key"),r.getString("original_name"),r.getLong("size_bytes"),r.getString("upload_status"))).list();
    }

    long insert(long request,long scholar,Map<String,Object> answers,Instant now) {
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO submissions(request_id,scholar_id,status,answers,version,submit_count,submitted_at,created_at,updated_at)
            VALUES(:request,:scholar,'SUBMITTED',%s,1,1,:now,:now,:now)
            """.formatted(jsonParameter)).param("request",request).param("scholar",scholar).param("json",json.writeValueAsString(answers))
            .param("now",UtcDateTimes.toDb(now)).update(key,"id");
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    int update(Submission previous,Map<String,Object> answers,String state,Instant now) {
        return jdbc.sql("""
            UPDATE submissions SET status=:state,answers=%s,version=version+1,submit_count=submit_count+1,
                submitted_at=COALESCE(submitted_at,:now),updated_at=:now
            WHERE id=:id AND version=:version AND status=:previous
            """.formatted(jsonParameter)).param("id",previous.id()).param("version",previous.version()).param("previous",previous.status())
            .param("state",state).param("json",json.writeValueAsString(answers)).param("now",UtcDateTimes.toDb(now)).update();
    }

    void history(long id,String from,String to,long actor,Map<String,Object> before,List<FileSnapshot> files,Instant now) {
        jdbc.sql("""
            INSERT INTO submission_histories(submission_id,from_status,to_status,actor_type,actor_id,snapshot,changed_at)
            VALUES(:id,:from,:to,'SCHOLAR',:actor,%s,:now)
            """.formatted(jsonParameter)).param("id",id).param("from",from,java.sql.Types.VARCHAR).param("to",to).param("actor",actor)
            .param("json",json.writeValueAsString(Map.of("answers",before,"files",files))).param("now",UtcDateTimes.toDb(now)).update();
    }

    void resolve(long revision,Instant now) {
        jdbc.sql("UPDATE revision_requests SET resolved_at=:now WHERE id=:id").param("id",revision).param("now",UtcDateTimes.toDb(now)).update();
    }
    record Submission(long id,String status,int version,Map<String,Object> answers,Instant submittedAt) {}
    record Request(String status,Instant closesAt,String completionMessage) {}
    record Revision(long id,Set<String> keys) {}
    record FileSnapshot(long id,String fieldKey,String originalName,long sizeBytes,String uploadStatus) {}
}
