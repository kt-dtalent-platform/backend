package kr.co.ktscholarship.backend.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ManagerDashboardRepository {
    private static final String SCOPE="""
        r.status='IN_PROGRESS' AND r.published_at IS NOT NULL
        AND (:program IS NULL OR r.program_id=:program)
        AND (:cohort IS NULL OR r.cohort_id=:cohort)
        """;
    private final JdbcClient jdbc;
    public ManagerDashboardRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private MapSqlParameterSource scope(Long program,Long cohort) {
        return new MapSqlParameterSource().addValue("program",program,java.sql.Types.BIGINT)
            .addValue("cohort",cohort,java.sql.Types.BIGINT);
    }
    List<RequestCounts> counts(Long program,Long cohort) {
        return jdbc.sql("""
            SELECT r.id,r.title,r.closes_at,COUNT(t.scholar_id) targets,
            SUM(CASE WHEN s.status='CONFIRMED' THEN 1 ELSE 0 END) confirmed,
            SUM(CASE WHEN s.status IN ('SUBMITTED','RESUBMITTED') THEN 1 ELSE 0 END) waiting,
            SUM(CASE WHEN s.status='RESUBMITTED' THEN 1 ELSE 0 END) resubmitted,
            SUM(CASE WHEN s.status='REVISION_REQUIRED' THEN 1 ELSE 0 END) revision
            FROM requests r LEFT JOIN request_targets t ON t.request_id=r.id
            LEFT JOIN submissions s ON s.request_id=t.request_id AND s.scholar_id=t.scholar_id
            WHERE """+" "+SCOPE+" GROUP BY r.id,r.title,r.closes_at ORDER BY r.closes_at,r.id")
            .paramSource(scope(program,cohort)).query((r,n)->new RequestCounts(r.getLong("id"),r.getString("title"),
                UtcDateTimes.fromDb(r.getObject("closes_at",LocalDateTime.class)),r.getLong("targets"),r.getLong("confirmed"),
                r.getLong("waiting"),r.getLong("resubmitted"),r.getLong("revision"))).list();
    }
    Optional<Oldest> oldest(Long program,Long cohort) {
        return jdbc.sql("""
            SELECT s.id,CASE WHEN s.status='RESUBMITTED' THEN s.updated_at ELSE s.submitted_at END waiting_since
            FROM submissions s JOIN request_targets t ON t.request_id=s.request_id AND t.scholar_id=s.scholar_id
            JOIN requests r ON r.id=s.request_id WHERE s.status IN ('SUBMITTED','RESUBMITTED') AND
            """+SCOPE+" ORDER BY CASE WHEN (CASE WHEN s.status='RESUBMITTED' THEN s.updated_at ELSE s.submitted_at END) IS NULL THEN 1 ELSE 0 END,waiting_since,s.id LIMIT 1")
            .paramSource(scope(program,cohort)).query((r,n)->new Oldest(r.getLong("id"),
                UtcDateTimes.fromDb(r.getObject("waiting_since",LocalDateTime.class)))).optional();
    }
    record RequestCounts(long id,String title,Instant closesAt,long targets,long confirmed,long waiting,long resubmitted,long revision) {}
    record Oldest(long id,Instant submittedAt) {}
}
