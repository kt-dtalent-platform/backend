package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubmissionHistoryRepository {
    private static final String WHERE=" WHERE s.scholar_id=:user AND s.status<>'DRAFT' AND (:status IS NULL OR s.status=:status) ";
    private final JdbcClient jdbc;
    public SubmissionHistoryRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private MapSqlParameterSource parameters(long user,String status) {
        return new MapSqlParameterSource().addValue("user",user).addValue("status",status,java.sql.Types.VARCHAR);
    }
    long count(long user,String status) {
        return jdbc.sql("SELECT COUNT(*) FROM submissions s"+WHERE).paramSource(parameters(user,status)).query(Long.class).single();
    }
    List<HistoryEntry> items(long user,String status,int page,int size) {
        return jdbc.sql("""
            SELECT s.request_id,r.title,s.status,s.submitted_at,s.confirmed_at FROM submissions s JOIN requests r ON r.id=s.request_id
            """+WHERE+" ORDER BY s.submitted_at DESC,s.id DESC LIMIT :limit OFFSET :offset")
            .paramSource(parameters(user,status).addValue("limit",size).addValue("offset",(long)(page-1)*size))
            .query((r,n)->new HistoryEntry(r.getLong("request_id"),r.getString("title"),r.getString("status"),
                UtcDateTimes.fromDb(r.getObject("submitted_at",LocalDateTime.class)),UtcDateTimes.fromDb(r.getObject("confirmed_at",LocalDateTime.class)),false)).list();
    }
    public record HistoryEntry(long requestId,String title,String status,Instant submittedAt,Instant confirmedAt,boolean purged) {}
}
