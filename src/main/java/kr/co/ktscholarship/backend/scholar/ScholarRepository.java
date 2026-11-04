package kr.co.ktscholarship.backend.scholar;

import java.sql.Types;
import java.util.Objects;
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
            AND (:keyword IS NULL OR LOCATE(:keyword, u.name) > 0 OR LOCATE(:keyword, u.email) > 0)
        """;

    private final JdbcClient jdbc;

    public ScholarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ScholarListResponse findAll(Long cohortId, String status, String keyword, int page, int pageSize) {
        var params = new MapSqlParameterSource()
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
                maskPhone(row.getString("phone")), row.getString("cohort_name"),
                row.getString("school_name"), row.getString("scholar_status"),
                row.getString("account_status")
            )).list();
        return new ScholarListResponse(items, page, pageSize, total);
    }

    private static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() != 10 && digits.length() != 11) {
            return "****";
        }
        int prefixLength = digits.startsWith("02") ? 2 : 3;
        return digits.substring(0, prefixLength) + "-****-" + digits.substring(digits.length() - 4);
    }
}
