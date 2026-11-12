package kr.co.ktscholarship.backend.scholar;

import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.PhoneMasker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScholarDetailRepository {

    private final JdbcClient jdbc;

    public ScholarDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScholarDetailResponse> findById(long scholarId) {
        return jdbc.sql("""
            SELECT u.id, u.name, u.email, s.phone, s.school_name, s.major,
                c.id AS cohort_id, c.name AS cohort_name, s.status,
                COALESCE(summary.total, 0) AS total, COALESCE(summary.confirmed, 0) AS confirmed
            FROM scholar_profiles s
            JOIN users u ON u.id = s.user_id
            JOIN cohorts c ON c.id = s.cohort_id
            LEFT JOIN (
                SELECT t.scholar_id, COUNT(DISTINCT r.id) AS total,
                    COUNT(DISTINCT CASE WHEN sub.status = 'CONFIRMED' THEN r.id END) AS confirmed
                FROM request_targets t
                JOIN requests r ON r.id = t.request_id
                LEFT JOIN submissions sub ON sub.request_id = r.id AND sub.scholar_id = t.scholar_id
                WHERE t.scholar_id = :scholarId AND r.status <> 'DRAFT' AND r.published_at IS NOT NULL
                GROUP BY t.scholar_id
            ) summary ON summary.scholar_id = u.id
            WHERE u.id = :scholarId AND u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            """)
            .param("scholarId", scholarId)
            .query((row, index) -> new ScholarDetailResponse(
                row.getLong("id"), row.getString("name"), row.getString("email"),
                PhoneMasker.mask(row.getString("phone")), row.getString("school_name"),
                row.getString("major"),
                new ScholarDetailResponse.Cohort(row.getLong("cohort_id"), row.getString("cohort_name")),
                row.getString("status"),
                new ScholarDetailResponse.SubmissionSummary(row.getLong("total"), row.getLong("confirmed"),
                    row.getLong("total") - row.getLong("confirmed"))
            )).optional();
    }
}
