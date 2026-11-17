package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.time.Instant;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScholarUpdateRepository {

    private final JdbcClient jdbc;

    public ScholarUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockExists(long scholarId) {
        return jdbc.sql("""
            SELECT u.id FROM users u JOIN scholar_profiles s ON s.user_id = u.id
            WHERE u.id = :id AND u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            FOR UPDATE
            """).param("id", scholarId).query(Long.class).optional()
            .isPresent();
    }

    public void update(long scholarId, ScholarUpdateRequest request, String schoolName, Instant updatedAt) {
        var params = new MapSqlParameterSource()
            .addValue("id", scholarId)
            .addValue("updatedAt", UtcDateTimes.toDb(updatedAt))
            .addValue("name", request.name(), Types.VARCHAR)
            .addValue("phone", request.phone(), Types.VARCHAR)
            .addValue("university", schoolName, Types.VARCHAR)
            .addValue("schoolId", request.schoolId(), Types.BIGINT)
            .addValue("major", request.major(), Types.VARCHAR);

        if (request.name() != null) {
            jdbc.sql("UPDATE users SET name = :name, updated_at = :updatedAt WHERE id = :id")
                .paramSource(params).update();
        }
        jdbc.sql("""
            UPDATE scholar_profiles
            SET phone = COALESCE(:phone, phone), school_name = COALESCE(:university, school_name),
                school_id = CASE WHEN :university IS NULL THEN school_id ELSE :schoolId END,
                major = COALESCE(:major, major), updated_at = :updatedAt
            WHERE user_id = :id
            """).paramSource(params).update();
    }
}
