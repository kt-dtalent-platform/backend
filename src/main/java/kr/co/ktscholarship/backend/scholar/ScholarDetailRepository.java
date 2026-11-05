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
                c.id AS cohort_id, c.name AS cohort_name, s.status
            FROM scholar_profiles s
            JOIN users u ON u.id = s.user_id
            JOIN cohorts c ON c.id = s.cohort_id
            WHERE u.id = :scholarId AND u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            """)
            .param("scholarId", scholarId)
            .query((row, index) -> new ScholarDetailResponse(
                row.getLong("id"), row.getString("name"), row.getString("email"),
                PhoneMasker.mask(row.getString("phone")), row.getString("school_name"),
                row.getString("major"),
                new ScholarDetailResponse.Cohort(row.getLong("cohort_id"), row.getString("cohort_name")),
                row.getString("status")
            )).optional();
    }
}
