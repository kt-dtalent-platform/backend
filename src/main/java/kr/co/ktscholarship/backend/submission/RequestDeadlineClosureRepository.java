package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestDeadlineClosureRepository {
    private final JdbcClient jdbc;
    public RequestDeadlineClosureRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

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
