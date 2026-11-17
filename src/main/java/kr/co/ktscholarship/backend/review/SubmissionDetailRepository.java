package kr.co.ktscholarship.backend.review;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionDetailRepository {

    private static final TypeReference<Map<String, Object>> ANSWERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SubmissionDetailRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 작성 중(DRAFT)은 장학생이 아직 내지 않은 것이라 없는 것으로 본다 */
    public Optional<Base> findSubmitted(long submissionId) {
        return jdbc.sql("""
            SELECT s.id, s.request_id, r.title, s.scholar_id, u.name AS scholar_name, c.name AS cohort_name,
                s.status, s.submitted_at, s.is_late, s.answers, s.version
            FROM submissions s
            JOIN requests r ON r.id = s.request_id
            JOIN users u ON u.id = s.scholar_id
            LEFT JOIN scholar_profiles p ON p.user_id = u.id
            LEFT JOIN cohorts c ON c.id = p.cohort_id
            WHERE s.id = :id AND s.status <> 'DRAFT'
            """)
            .param("id", submissionId)
            .query((row, index) -> new Base(
                row.getLong("id"), row.getLong("request_id"), row.getString("title"),
                new SubmissionDetailResponse.Scholar(
                    row.getLong("scholar_id"), row.getString("scholar_name"), row.getString("cohort_name")),
                row.getString("status"),
                UtcDateTimes.fromDb(row.getObject("submitted_at", LocalDateTime.class)),
                row.getBoolean("is_late"),
                parse(row.getString("answers")),
                row.getInt("version")))
            .optional();
    }

    public List<SubmissionDetailResponse.Field> findFields(long requestId) {
        return jdbc.sql("""
            SELECT field_key, label, field_type FROM request_fields
            WHERE request_id = :id ORDER BY sort_order, id
            """)
            .param("id", requestId)
            .query((row, index) -> new SubmissionDetailResponse.Field(
                row.getString("field_key"), row.getString("label"), row.getString("field_type")))
            .list();
    }

    public List<SubmissionDetailResponse.File> findUploadedFiles(long submissionId) {
        return jdbc.sql("""
            SELECT id, field_key, original_name, content_type, size_bytes FROM submission_files
            WHERE submission_id = :id AND upload_status = 'UPLOADED' ORDER BY id
            """)
            .param("id", submissionId)
            .query((row, index) -> new SubmissionDetailResponse.File(
                row.getLong("id"), row.getString("field_key"), row.getString("original_name"),
                row.getString("content_type"), row.getLong("size_bytes")))
            .list();
    }

    public List<SubmissionDetailResponse.History> findHistory(long submissionId) {
        return jdbc.sql("""
            SELECT from_status, to_status, actor_type, changed_at FROM submission_histories
            WHERE submission_id = :id ORDER BY changed_at, id
            """)
            .param("id", submissionId)
            .query((row, index) -> new SubmissionDetailResponse.History(
                row.getString("from_status"), row.getString("to_status"), row.getString("actor_type"),
                UtcDateTimes.fromDb(row.getObject("changed_at", LocalDateTime.class))))
            .list();
    }

    public List<SubmissionDetailResponse.Revision> findRevisions(long submissionId) {
        // 보완 요청 하나에 항목이 여러 개라, 요청 순서를 지키며 항목을 모은다
        Map<Long, RevisionRow> revisions = new LinkedHashMap<>();
        jdbc.sql("""
            SELECT r.id, r.reason, r.resolved_at, f.field_key
            FROM revision_requests r
            LEFT JOIN revision_request_fields f ON f.revision_request_id = r.id
            WHERE r.submission_id = :id
            ORDER BY r.requested_at DESC, r.id DESC, f.id
            """)
            .param("id", submissionId)
            .query((row, index) -> {
                String reason = row.getString("reason");
                java.time.Instant resolvedAt = UtcDateTimes.fromDb(row.getObject("resolved_at", LocalDateTime.class));
                RevisionRow revision = revisions.computeIfAbsent(row.getLong("id"),
                    key -> new RevisionRow(reason, resolvedAt, new java.util.ArrayList<>()));
                String fieldKey = row.getString("field_key");
                if (fieldKey != null) {
                    revision.fieldKeys().add(fieldKey);
                }
                return revision;
            })
            .list();
        return revisions.values().stream()
            .map(r -> new SubmissionDetailResponse.Revision(r.fieldKeys(), r.reason(), null, r.resolvedAt()))
            .toList();
    }

    private Map<String, Object> parse(String answersJson) {
        if (answersJson == null) {
            return Map.of();
        }
        try {
            Map<String, Object> answers = json.readValue(answersJson, ANSWERS);
            return answers == null ? Map.of() : answers;
        } catch (JacksonException exception) {
            return Map.of();
        }
    }

    public record Base(long id, long requestId, String requestTitle, SubmissionDetailResponse.Scholar scholar,
                       String status, java.time.Instant submittedAt, boolean late, Map<String, Object> answers,
                       int version) {
    }

    private record RevisionRow(String reason, java.time.Instant resolvedAt, List<String> fieldKeys) {
    }
}
