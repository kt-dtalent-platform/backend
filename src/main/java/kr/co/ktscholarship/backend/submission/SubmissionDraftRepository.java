package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionDraftRepository {
    private static final TypeReference<Map<String, Object>> ANSWERS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String jsonParameter;

    public SubmissionDraftRepository(JdbcClient jdbc, JsonMapper json, DataSource dataSource) throws MetaDataAccessException {
        this.jdbc = jdbc;
        this.json = json;
        // H2는 VARCHAR를 JSON으로 CAST하면 객체가 아닌 JSON 문자열로 저장한다.
        String database = JdbcUtils.extractDatabaseMetaData(dataSource, java.sql.DatabaseMetaData::getDatabaseProductName);
        this.jsonParameter = "H2".equals(database) ? ":answers FORMAT JSON" : "CAST(:answers AS JSON)";
    }

    // 기존 대상 행을 먼저 잠가 동일 학생의 최초 저장도 직렬화한다.
    boolean lockTarget(long requestId, long scholarId) {
        return jdbc.sql("SELECT id FROM request_targets WHERE request_id = :request AND scholar_id = :scholar FOR UPDATE")
            .param("request", requestId).param("scholar", scholarId).query(Long.class).optional().isPresent();
    }

    Optional<Request> findRequest(long id) {
        return jdbc.sql("SELECT status, closes_at FROM requests WHERE id = :id AND published_at IS NOT NULL AND status <> 'DRAFT'")
            .param("id", id).query((rs, n) -> new Request(rs.getString("status"),
                UtcDateTimes.fromDb(rs.getObject("closes_at", LocalDateTime.class)))).optional();
    }

    Optional<Submission> findSubmission(long requestId, long scholarId) {
        return jdbc.sql("SELECT id, status, version, answers FROM submissions WHERE request_id = :request AND scholar_id = :scholar FOR UPDATE")
            .param("request", requestId).param("scholar", scholarId)
            .query((rs, n) -> new Submission(rs.getLong("id"), rs.getString("status"), rs.getInt("version"),
                json.readValue(rs.getString("answers"), ANSWERS))).optional();
    }

    List<Field> fields(long requestId) {
        return jdbc.sql("SELECT field_key, field_type, required, options, validation FROM request_fields WHERE request_id = :id ORDER BY sort_order, id")
            .param("id", requestId).query((rs, n) -> new Field(rs.getString("field_key"), rs.getString("field_type"),
                rs.getString("options") == null ? List.of() : json.readValue(rs.getString("options"), List.class),
                rs.getString("validation") == null ? Map.of() : json.readValue(rs.getString("validation"), ANSWERS), rs.getBoolean("required"))).list();
    }

    Optional<Set<String>> revisionFields(long submissionId) {
        return jdbc.sql("SELECT id FROM revision_requests WHERE submission_id = :id AND resolved_at IS NULL ORDER BY requested_at DESC, id DESC LIMIT 1")
            .param("id", submissionId).query(Long.class).optional().map(id -> new HashSet<>(jdbc.sql(
                "SELECT field_key FROM revision_request_fields WHERE revision_request_id = :id")
                .param("id", id).query(String.class).list()));
    }

    long insert(long requestId, long scholarId, Map<String, Object> answers, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO submissions (request_id, scholar_id, status, answers, version, created_at, updated_at)
            VALUES (:request, :scholar, 'DRAFT', %s, 1, :now, :now)
            """.formatted(jsonParameter)).param("request", requestId).param("scholar", scholarId)
            .param("answers", json.writeValueAsString(answers)).param("now", UtcDateTimes.toDb(now)).update(key, "id");
        return key.getKey().longValue();
    }

    int update(Submission submission, Map<String, Object> answers, Instant now) {
        return jdbc.sql("""
            UPDATE submissions SET answers = %s, version = version + 1, updated_at = :now
            WHERE id = :id AND version = :version AND status = :status
            """.formatted(jsonParameter)).param("id", submission.id()).param("version", submission.version()).param("status", submission.status())
            .param("answers", json.writeValueAsString(answers)).param("now", UtcDateTimes.toDb(now)).update();
    }

    record Request(String status, Instant closesAt) {}
    record Submission(long id, String status, int version, Map<String, Object> answers) {}
    record Field(String key, String type, List<?> options, Map<String, Object> validation, boolean required) {}
}
