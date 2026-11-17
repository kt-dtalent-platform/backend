package kr.co.ktscholarship.backend.auth;

import java.util.Optional;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MeProfileRepository {
    private final JdbcClient jdbc;
    public MeProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<MeResponse.ScholarProfile> find(long userId) {
        return jdbc.sql("""
            SELECT c.id, c.name, s.school_name
            FROM scholar_profiles s JOIN cohorts c ON c.id=s.cohort_id JOIN users u ON u.id=s.user_id
            WHERE s.user_id=:id AND u.role='SCHOLAR' AND u.deleted_at IS NULL
            """).param("id", userId).query((rs, n) -> new MeResponse.ScholarProfile(
                new MeResponse.Cohort(rs.getLong("id"), rs.getString("name")), rs.getString("school_name")
            )).optional();
    }
}
