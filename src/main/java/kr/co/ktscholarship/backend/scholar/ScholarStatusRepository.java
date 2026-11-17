package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScholarStatusRepository {
    private final JdbcClient jdbc;
    public ScholarStatusRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Row> lock(List<Long> ids) {
        return jdbc.sql("""
            SELECT s.user_id, s.status, s.completed_year, s.withdrawal_reason,
                   s.withdrawal_reason_detail, s.updated_at
            FROM scholar_profiles s JOIN users u ON u.id = s.user_id
            WHERE s.user_id IN (:ids) AND u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            ORDER BY s.user_id FOR UPDATE
            """).param("ids", ids).query((rs, n) -> new Row(
                rs.getLong("user_id"), rs.getString("status"), rs.getObject("completed_year", Integer.class),
                rs.getString("withdrawal_reason"), rs.getString("withdrawal_reason_detail"),
                UtcDateTimes.fromDb(rs.getObject("updated_at", LocalDateTime.class))
            )).list();
    }

    public void update(long id, ScholarStatusRequest change, Instant now) {
        jdbc.sql("""
            UPDATE scholar_profiles SET status=:status, completed_year=:year,
                withdrawal_reason=:reason, withdrawal_reason_detail=:detail, updated_at=:now
            WHERE user_id=:id
            """).param("status", change.status().name())
            .param("year", change.completedYear(), Types.SMALLINT)
            .param("reason", change.withdrawalReason() == null ? null : change.withdrawalReason().name(), Types.VARCHAR)
            .param("detail", change.withdrawalReasonDetail(), Types.VARCHAR)
            .param("now", UtcDateTimes.toDb(now)).param("id", id).update();
    }

    public record Row(long id, String status, Integer year, String reason, String detail, Instant updatedAt) {}
}
