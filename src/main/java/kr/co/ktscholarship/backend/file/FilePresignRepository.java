package kr.co.ktscholarship.backend.file;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class FilePresignRepository {

    private static final TypeReference<Map<String, Object>> VALIDATION_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public FilePresignRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Submission> findSubmission(long submissionId) {
        return jdbc.sql("SELECT id, request_id, scholar_id, status FROM submissions WHERE id = :id")
            .param("id", submissionId)
            .query((row, index) -> new Submission(
                row.getLong("id"), row.getLong("request_id"), row.getLong("scholar_id"), row.getString("status")
            )).optional();
    }

    public Optional<Field> findField(long requestId, String fieldKey) {
        return jdbc.sql("""
            SELECT field_key, field_type, validation FROM request_fields
            WHERE request_id = :requestId AND field_key = :fieldKey
            """).param("requestId", requestId).param("fieldKey", fieldKey)
            .query((row, index) -> {
                String validation = row.getString("validation");
                return new Field(row.getString("field_key"), row.getString("field_type"),
                    validation == null ? null : mapper.readValue(validation, VALIDATION_TYPE));
            }).optional();
    }

    public boolean isRevisionField(long submissionId, String fieldKey) {
        return Boolean.TRUE.equals(jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM revision_requests r
                JOIN revision_request_fields f ON f.revision_request_id = r.id
                WHERE r.id = (
                    SELECT latest.id FROM revision_requests latest
                    WHERE latest.submission_id = :submissionId AND latest.resolved_at IS NULL
                    ORDER BY latest.requested_at DESC, latest.id DESC LIMIT 1
                ) AND f.field_key = :fieldKey
            )
            """).param("submissionId", submissionId).param("fieldKey", fieldKey).query(Boolean.class).single());
    }

    public long countActiveFiles(long submissionId, String fieldKey) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM submission_files
            WHERE submission_id = :submissionId AND field_key = :fieldKey
                AND upload_status IN ('PENDING', 'UPLOADED')
            """).param("submissionId", submissionId).param("fieldKey", fieldKey).query(Long.class).single();
    }

    // 발급 처리에 필요한 내부 조회 모델. HTTP 응답으로 직접 사용하지 않습니다.
    public record Submission(long id, long requestId, long scholarId, String status) {
    }

    public record Field(String fieldKey, String fieldType, Map<String, Object> validation) {
    }
}
