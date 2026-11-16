package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScholarExportRepository {
    private final JdbcClient jdbc;
    public ScholarExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Row> find(Long cohort, Set<String> states) {
        String filter = states.isEmpty() ? "" : " AND s.status IN (:states)";
        var query = jdbc.sql("""
            SELECT u.name, u.email, u.status AS account_status, c.name AS cohort,
                s.school_name, s.major, s.student_no, s.phone, s.status,
                s.completed_year, s.withdrawal_reason, s.withdrawal_reason_detail
            FROM scholar_profiles s JOIN users u ON u.id=s.user_id JOIN cohorts c ON c.id=s.cohort_id
            WHERE u.role='SCHOLAR' AND u.deleted_at IS NULL
                AND (:cohort IS NULL OR c.id=:cohort)
            """ + filter + " ORDER BY c.started_on DESC, c.created_at DESC, c.id DESC, u.name ASC, u.id ASC")
            .param("cohort", cohort, Types.BIGINT);
        if (!states.isEmpty()) query.param("states", states);
        return query.query((rs, n) -> new Row(rs.getString("name"), rs.getString("email"),
            rs.getString("account_status"), rs.getString("cohort"), rs.getString("school_name"),
            rs.getString("major"), rs.getString("student_no"), rs.getString("phone"), rs.getString("status"),
            rs.getObject("completed_year", Integer.class), rs.getString("withdrawal_reason"),
            rs.getString("withdrawal_reason_detail"))).list();
    }

    public void audit(long actor, int count, String ip, Instant now) {
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id,action,target_type,row_count,ip_address,created_at)
            VALUES(:actor,'EXPORT_SCHOLARS','SCHOLAR',:count,:ip,:now)
            """).param("actor", actor).param("count", count).param("ip", ip, Types.VARCHAR)
            .param("now", UtcDateTimes.toDb(now)).update();
    }

    public record Row(String name, String email, String accountStatus, String cohort, String university,
        String major, String studentNo, String phone, String status, Integer completedYear,
        String withdrawalReason, String withdrawalReasonDetail) {}
}
