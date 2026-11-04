package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScholarUpdateRepository {

    private final JdbcClient jdbc;

    public ScholarUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ScholarUpdateResponse update(long scholarId, ScholarUpdateRequest request) {
        jdbc.sql("""
            SELECT u.id FROM users u JOIN scholar_profiles s ON s.user_id = u.id
            WHERE u.id = :id AND u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            FOR UPDATE
            """).param("id", scholarId).query(Long.class).optional()
            .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND));

        LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        var params = new MapSqlParameterSource()
            .addValue("id", scholarId)
            .addValue("updatedAt", updatedAt)
            .addValue("name", request.name(), Types.VARCHAR)
            .addValue("phone", request.phone(), Types.VARCHAR)
            .addValue("university", request.university(), Types.VARCHAR)
            .addValue("major", request.major(), Types.VARCHAR);

        if (request.name() != null) {
            jdbc.sql("UPDATE users SET name = :name, updated_at = :updatedAt WHERE id = :id")
                .paramSource(params).update();
        }
        jdbc.sql("""
            UPDATE scholar_profiles
            SET phone = COALESCE(:phone, phone), school_name = COALESCE(:university, school_name),
                major = COALESCE(:major, major), updated_at = :updatedAt
            WHERE user_id = :id
            """).paramSource(params).update();
        return new ScholarUpdateResponse(scholarId, updatedAt);
    }
}
