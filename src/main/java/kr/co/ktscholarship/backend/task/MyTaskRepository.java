package kr.co.ktscholarship.backend.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MyTaskRepository {

    public enum Filter { TODO, ALL }
    public enum Sort { DUE_ASC, CREATED_DESC }

    // API 응답 연결 전 내부 조회 결과이며, requestDueAt은 개별 연장을 적용하기 전 공통 마감일입니다.
    public record TaskRow(long requestId, String title, LocalDateTime requestDueAt, String status, String revisionReason) {
    }

    private final JdbcClient jdbc;

    public MyTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaskRow> find(long scholarId, Filter filter, Sort sort) {
        return jdbc.sql("""
            SELECT r.id, r.title, r.closes_at, s.status AS submission_status,
                CASE WHEN s.status = 'REVISION_REQUIRED' THEN (
                    SELECT rr.reason FROM revision_requests rr
                    WHERE rr.submission_id = s.id AND rr.resolved_at IS NULL
                    ORDER BY rr.requested_at DESC, rr.id DESC LIMIT 1
                ) END AS revision_reason
            FROM requests r
            LEFT JOIN submissions s ON s.request_id = r.id AND s.scholar_id = :scholarId
            WHERE r.status = 'IN_PROGRESS'
                AND EXISTS (
                    SELECT 1 FROM request_targets t
                    WHERE t.request_id = r.id AND t.scholar_id = :scholarId
                )
                AND (:todoOnly = FALSE OR s.id IS NULL OR s.status IN ('DRAFT', 'REVISION_REQUIRED'))
            ORDER BY
                CASE WHEN :createdDesc THEN r.created_at END DESC,
                CASE WHEN NOT :createdDesc THEN r.closes_at END ASC
            """)
            .param("scholarId", scholarId)
            .param("todoOnly", filter == null || filter == Filter.TODO)
            .param("createdDesc", sort == Sort.CREATED_DESC)
            .query((row, index) -> new TaskRow(
                row.getLong("id"), row.getString("title"), row.getObject("closes_at", LocalDateTime.class),
                Objects.requireNonNullElse(row.getString("submission_status"), "NOT_SUBMITTED"),
                row.getString("revision_reason")
            )).list();
    }
}
