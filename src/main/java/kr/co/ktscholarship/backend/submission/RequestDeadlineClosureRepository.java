package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestDeadlineClosureRepository {
    private final JdbcClient jdbc;
    public RequestDeadlineClosureRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public java.util.Optional<String> lockStatus(long id) {
        return jdbc.sql("SELECT status FROM requests WHERE id=:id AND published_at IS NOT NULL FOR UPDATE")
            .param("id",id).query(String.class).optional();
    }

    public void close(long id, Instant now) {
        jdbc.sql("UPDATE requests SET status='CLOSED',updated_at=:now WHERE id=:id")
            .param("id",id).param("now",UtcDateTimes.toDb(now)).update();
    }

    public void audit(long id,long actor,Instant now) {
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id,action,target_type,target_id,row_count,created_at)
            VALUES(:actor,'CLOSE_REQUEST','REQUEST',:id,1,:now)
            """).param("actor",actor).param("id",id).param("now",UtcDateTimes.toDb(now)).update();
    }

    public int closeExpired(Instant now) {
        return jdbc.sql("""
            UPDATE requests SET status='CLOSED', updated_at=:now
            WHERE status='IN_PROGRESS' AND published_at IS NOT NULL AND closes_at<=:now
              AND NOT EXISTS (
                SELECT 1 FROM request_targets t
                WHERE t.request_id=requests.id AND t.extended_due_at>:now
              )
              AND NOT EXISTS (
                SELECT 1 FROM request_targets t
                LEFT JOIN submissions s ON s.request_id=t.request_id AND s.scholar_id=t.scholar_id
                WHERE t.request_id=requests.id AND (s.id IS NULL OR s.status<>'CONFIRMED')
              )
            """).param("now", UtcDateTimes.toDb(now)).update();
    }
}
