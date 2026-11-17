package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TargetDeadlineRepository {
    private final JdbcClient jdbc;
    public TargetDeadlineRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Target> find(long request, long scholar, boolean lock) {
        return jdbc.sql("""
            SELECT t.id, r.closes_at, r.status, r.published_at, t.extended_due_at
            FROM request_targets t JOIN requests r ON r.id=t.request_id JOIN users u ON u.id=t.scholar_id
            WHERE t.request_id=:request AND t.scholar_id=:scholar AND u.role='SCHOLAR' AND u.deleted_at IS NULL
            """ + (lock ? " FOR UPDATE" : "")).param("request", request).param("scholar", scholar)
            .query((rs,n)->new Target(rs.getLong("id"),UtcDateTimes.fromDb(rs.getObject("closes_at",LocalDateTime.class)),
                UtcDateTimes.fromDb(rs.getObject("extended_due_at",LocalDateTime.class)),rs.getString("status"),
                rs.getObject("published_at")!=null)).optional();
    }

    public void extend(long target, Instant dueAt, long actor, Instant now) {
        jdbc.sql("UPDATE request_targets SET extended_due_at=:due WHERE id=:target")
            .param("due",UtcDateTimes.toDb(dueAt)).param("target",target).update();
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id,action,target_type,target_id,row_count,created_at)
            VALUES(:actor,'EXTEND_DEADLINE','REQUEST_TARGET',:target,1,:now)
            """).param("actor",actor).param("target",target).param("now",UtcDateTimes.toDb(now)).update();
    }
    public record Target(long id,Instant commonDueAt, Instant extendedDueAt, String status, boolean published) {
        Instant dueAt() { return extendedDueAt == null ? commonDueAt : extendedDueAt; }
    }
}
