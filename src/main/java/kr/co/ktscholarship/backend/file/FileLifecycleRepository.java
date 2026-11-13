package kr.co.ktscholarship.backend.file;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FileLifecycleRepository {
    private final JdbcClient jdbc;
    public FileLifecycleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<FileRow> find(long id, boolean lock) {
        return jdbc.sql("""
            SELECT id, submission_id, field_key, s3_key, original_name, content_type, size_bytes, upload_status, created_at
            FROM submission_files WHERE id=:id
            """ + (lock ? " FOR UPDATE" : ""))
            .param("id",id).query((r,n) -> new FileRow(r.getLong("id"), r.getLong("submission_id"),
                r.getString("field_key"),r.getString("s3_key"),r.getString("original_name"),r.getString("content_type"),
                r.getLong("size_bytes"),r.getString("upload_status"),UtcDateTimes.fromDb(r.getObject("created_at",LocalDateTime.class)))).optional();
    }

    public void uploaded(long id, long size, Instant now) {
        jdbc.sql("UPDATE submission_files SET upload_status='UPLOADED',size_bytes=:size,uploaded_at=:now WHERE id=:id")
            .param("id",id).param("size",size).param("now",UtcDateTimes.toDb(now)).update();
    }

    public void deleted(long id) {
        jdbc.sql("UPDATE submission_files SET upload_status='DELETED' WHERE id=:id").param("id",id).update();
    }

    public List<Long> stalePending(Instant before) {
        return jdbc.sql("SELECT id FROM submission_files WHERE upload_status='PENDING' AND created_at<:before ORDER BY id LIMIT 100")
            .param("before",UtcDateTimes.toDb(before)).query(Long.class).list();
    }

    public record FileRow(long id, long submissionId, String fieldKey, String key, String originalName,
                          String contentType, long size, String status, Instant createdAt) {}
}
