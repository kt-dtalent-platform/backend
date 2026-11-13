package kr.co.ktscholarship.backend.request;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Objects;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RequestListRepository {

    // 목록과 전체 건수(total)가 같은 조건을 쓰도록 한 곳에 둔다
    private static final String WHERE_SQL = """
        WHERE (:programId IS NULL OR r.program_id = :programId)
            AND (:cohortId IS NULL OR r.cohort_id = :cohortId)
            AND (:status IS NULL OR r.status = :status)
        """;

    // 대상자와 제출을 "같은 요청 AND 같은 사람"으로 붙여 대상자 1명당 최대 1행이 되게 한다
    // (요청 기준으로만 붙이면 대상자 수 x 제출 수만큼 행이 늘어 숫자가 부풀어 오른다)
    private static final String LIST_SQL = """
        SELECT r.id, r.title, tpl.code AS template_code, c.name AS cohort_name, r.status,
            r.published_at, r.closes_at,
            COUNT(t.id) AS target_count,
            COALESCE(SUM(CASE WHEN s.status IS NOT NULL AND s.status <> 'DRAFT' THEN 1 ELSE 0 END), 0)
                AS submitted_count,
            COALESCE(SUM(CASE WHEN s.status = 'REVISION_REQUIRED' THEN 1 ELSE 0 END), 0) AS revision_count
        FROM requests r
        JOIN request_templates tpl ON tpl.id = r.template_id
        LEFT JOIN cohorts c ON c.id = r.cohort_id
        LEFT JOIN request_targets t ON t.request_id = r.id
        LEFT JOIN submissions s ON s.request_id = t.request_id AND s.scholar_id = t.scholar_id
        """ + WHERE_SQL + """
        GROUP BY r.id, r.title, tpl.code, c.name, r.status, r.published_at, r.closes_at, r.created_at
        ORDER BY CASE r.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
            r.created_at DESC, r.id DESC
        LIMIT :limit OFFSET :offset
        """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM requests r " + WHERE_SQL;

    private final JdbcClient jdbc;

    public RequestListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RequestListResponse findAll(Long programId, Long cohortId, RequestStatus status, int page, int pageSize) {
        var params = new MapSqlParameterSource()
            .addValue("programId", programId, Types.BIGINT)
            .addValue("cohortId", cohortId, Types.BIGINT)
            .addValue("status", status == null ? null : status.name(), Types.VARCHAR);

        long total = Objects.requireNonNull(
            jdbc.sql(COUNT_SQL).paramSource(params).query(Long.class).single(),
            "COUNT(*) result must not be null"
        );

        params.addValue("limit", pageSize);
        params.addValue("offset", (long) (page - 1) * pageSize);
        var items = jdbc.sql(LIST_SQL)
            .paramSource(params)
            .query((row, index) -> new RequestListResponse.Item(
                row.getLong("id"),
                row.getString("title"),
                row.getString("template_code"),
                row.getString("cohort_name"),
                RequestStatus.valueOf(row.getString("status")),
                UtcDateTimes.fromDb(row.getObject("published_at", LocalDateTime.class)),
                UtcDateTimes.fromDb(row.getObject("closes_at", LocalDateTime.class)),
                row.getLong("target_count"),
                row.getLong("submitted_count"),
                row.getLong("revision_count")
            )).list();

        return new RequestListResponse(items, page, pageSize, total);
    }
}
