package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.util.Objects;
import kr.co.ktscholarship.backend.common.util.PhoneMasker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScholarRepository {

    private static final String FROM_SQL = """
        FROM scholar_profiles s
        JOIN users u ON u.id = s.user_id
        JOIN cohorts c ON c.id = s.cohort_id
        WHERE u.role = 'SCHOLAR' AND u.deleted_at IS NULL
            AND (:cohortId IS NULL OR s.cohort_id = :cohortId)
            AND (:status IS NULL OR s.status = :status)
            AND (:requestId IS NULL OR (
                EXISTS (SELECT 1 FROM request_targets t
                    WHERE t.request_id = :requestId AND t.scholar_id = u.id)
                AND ((:submitStatus = 'NOT_SUBMITTED' AND NOT EXISTS (
                    SELECT 1 FROM submissions sub WHERE sub.request_id = :requestId AND sub.scholar_id = u.id
                )) OR EXISTS (
                    SELECT 1 FROM submissions sub WHERE sub.request_id = :requestId AND sub.scholar_id = u.id
                        -- ENUM을 문자열로 비교해 NOT_SUBMITTED도 H2/MySQL에서 안전하게 평가한다.
                        AND CONCAT('', sub.status) = :submitStatus
                ))
            ))
            AND (:keyword IS NULL OR LOCATE(:keyword, u.name) > 0 OR LOCATE(:keyword, u.email) > 0)
        """;

    private final JdbcClient jdbc;

    public ScholarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ScholarListResponse findAll(
        Long cohortId, String status, String keyword, Long requestId, String submitStatus, int page, int pageSize
    ) {
        var params = new MapSqlParameterSource()
            .addValue("requestId", requestId, Types.BIGINT)
            .addValue("submitStatus", submitStatus, Types.VARCHAR)
            .addValue("cohortId", cohortId, Types.BIGINT)
            .addValue("status", status, Types.VARCHAR)
            .addValue("keyword", keyword == null || keyword.isBlank() ? null : keyword, Types.VARCHAR);
        long total = Objects.requireNonNull(
            jdbc.sql("SELECT COUNT(*) " + FROM_SQL).paramSource(params).query(Long.class).single(),
            "COUNT(*) result must not be null"
        );
        params.addValue("limit", pageSize);
        params.addValue("offset", (long) (page - 1) * pageSize);
        var items = jdbc.sql("""
            SELECT u.id, u.name, u.email, s.phone, c.name AS cohort_name,
                s.school_name, s.status AS scholar_status, u.status AS account_status
            """ + FROM_SQL + " ORDER BY u.id LIMIT :limit OFFSET :offset")
            .paramSource(params)
            .query((row, index) -> new ScholarListResponse.Item(
                row.getLong("id"), row.getString("name"), row.getString("email"),
                PhoneMasker.mask(row.getString("phone")), row.getString("cohort_name"),
                row.getString("school_name"), row.getString("scholar_status"),
                row.getString("account_status")
            )).list();
        return new ScholarListResponse(items, page, pageSize, total);
    }
}
