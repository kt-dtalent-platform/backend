package kr.co.ktscholarship.backend.analysis;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DocumentAnalysisRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<AnswerComparison.Item>> ITEMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final boolean h2;

    public DocumentAnalysisRepository(JdbcClient jdbc, JsonMapper json, DataSource dataSource)
        throws MetaDataAccessException {
        this.jdbc = jdbc;
        this.json = json;
        // H2(테스트)는 VARCHAR를 JSON으로 CAST하면 객체가 아닌 JSON 문자열로 저장해서 표기를 바꾼다
        this.h2 = "H2".equals(JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName));
    }

    public Optional<Submission> findSubmission(long submissionId) {
        return jdbc.sql("""
            SELECT s.id, s.scholar_id, s.status, s.answers, r.academic_year, r.semester
            FROM submissions s JOIN requests r ON r.id = s.request_id
            WHERE s.id = :id
            """)
            .param("id", submissionId)
            .query((row, index) -> new Submission(
                row.getLong("id"), row.getLong("scholar_id"), row.getString("status"),
                readMap(row.getString("answers")),
                new TargetSemester(row.getInt("academic_year"), row.getString("semester"))))
            .optional();
    }

    public Optional<StoredFile> findFile(long submissionId, long fileId) {
        return jdbc.sql("""
            SELECT id, s3_key, original_name, size_bytes, upload_status
            FROM submission_files WHERE id = :id AND submission_id = :submission
            """)
            .param("id", fileId)
            .param("submission", submissionId)
            .query((row, index) -> new StoredFile(row.getLong("id"), row.getString("s3_key"),
                row.getString("original_name"), row.getLong("size_bytes"), row.getString("upload_status")))
            .optional();
    }

    /** 저장 트랜잭션 안에서 제출 행을 잠그고 지금 입력값을 읽는다 */
    public Map<String, Object> lockAnswers(long submissionId) {
        return jdbc.sql("SELECT answers FROM submissions WHERE id = :id FOR UPDATE")
            .param("id", submissionId)
            .query((row, index) -> readMap(row.getString("answers")))
            .single();
    }

    /**
     * 결과를 새 회차로 저장한다. 새 id를 돌려준다.
     * current면 같은 제출의 이전 결과를 무효 처리하고, 아니면(분석 중 입력값이 바뀜) 이 결과만 처음부터 무효로 남긴다
     */
    public long save(long submissionId, long fileId, long requestedBy, Saved saved, boolean current) {
        int attempt = jdbc.sql("SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM document_analysis_results WHERE file_id = :file")
            .param("file", fileId)
            .query(Integer.class)
            .single();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO document_analysis_results (submission_id, file_id, attempt_number, status, entered_values,
                extracted_values, comparisons, overall_result, failure_reason, analyzer_version, requested_by,
                requested_at, completed_at, invalidated_at)
            VALUES (:submission, :file, :attempt, :status, %s, %s, %s, :overall, :reason, :version, :requestedBy,
                :requestedAt, :completedAt, :invalidatedAt)
            """.formatted(jsonParam("entered"), jsonParam("extracted"), jsonParam("comparisons")))
            .param("submission", submissionId)
            .param("file", fileId)
            .param("attempt", attempt)
            .param("status", saved.status())
            .param("entered", json.writeValueAsString(saved.entered()))
            .param("extracted", json.writeValueAsString(saved.extracted()))
            .param("comparisons", json.writeValueAsString(saved.comparison().comparisons()))
            .param("overall", saved.comparison().overallResult().name())
            .param("reason", saved.failureReason())
            .param("version", saved.analyzerVersion())
            .param("requestedBy", requestedBy)
            .param("requestedAt", UtcDateTimes.toDb(saved.requestedAt()))
            .param("completedAt", UtcDateTimes.toDb(saved.completedAt()))
            .param("invalidatedAt", current ? null : UtcDateTimes.toDb(saved.completedAt()), Types.TIMESTAMP)
            .update(key, "id");
        long id = Objects.requireNonNull(key.getKey()).longValue();
        if (!current) {
            return id;
        }
        jdbc.sql("""
            UPDATE document_analysis_results SET invalidated_at = :now
            WHERE submission_id = :submission AND id <> :id AND invalidated_at IS NULL
            """)
            .param("now", UtcDateTimes.toDb(saved.completedAt()))
            .param("submission", submissionId)
            .param("id", id)
            .update();
        return id;
    }

    public Optional<Stored> findById(long analysisId) {
        return jdbc.sql(SELECT + " WHERE a.id = :id")
            .param("id", analysisId)
            .query((row, index) -> stored(row))
            .optional();
    }

    /** 제출의 최신 유효 결과: 무효 처리되지 않았고, 분석한 파일이 아직 업로드 상태인 것 */
    public Optional<Stored> findLatestValid(long submissionId) {
        return jdbc.sql(SELECT + """
             WHERE a.submission_id = :submission AND a.invalidated_at IS NULL AND f.upload_status = 'UPLOADED'
             ORDER BY a.requested_at DESC, a.id DESC LIMIT 1
            """)
            .param("submission", submissionId)
            .query((row, index) -> stored(row))
            .optional();
    }

    private static final String SELECT = """
        SELECT a.id, a.submission_id, a.file_id, a.attempt_number, a.status, a.entered_values, a.comparisons,
            a.overall_result, a.failure_reason, a.requested_at, a.completed_at, s.scholar_id, s.answers
        FROM document_analysis_results a
        JOIN submissions s ON s.id = a.submission_id
        JOIN submission_files f ON f.id = a.file_id
        """;

    private Stored stored(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Stored(
            row.getLong("id"), row.getLong("submission_id"), row.getLong("file_id"), row.getInt("attempt_number"),
            row.getString("status"),
            AnswerComparison.Result.valueOf(row.getString("overall_result")),
            json.readValue(row.getString("comparisons"), ITEMS),
            row.getString("failure_reason"),
            json.readValue(row.getString("entered_values"), STRINGS),
            row.getLong("scholar_id"),
            readMap(row.getString("answers")),
            UtcDateTimes.fromDb(row.getObject("requested_at", LocalDateTime.class)),
            UtcDateTimes.fromDb(row.getObject("completed_at", LocalDateTime.class)));
    }

    private Map<String, Object> readMap(String value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> map = json.readValue(value, MAP);
        return map == null ? Map.of() : map;
    }

    private String jsonParam(String name) {
        return h2 ? ":" + name + " FORMAT JSON" : "CAST(:" + name + " AS JSON)";
    }

    public record Submission(long id, long scholarId, String status, Map<String, Object> answers, TargetSemester target) {
    }

    public record StoredFile(long id, String s3Key, String originalName, long sizeBytes, String uploadStatus) {
    }

    public record Saved(String status, Map<String, String> entered, Object extracted, AnswerComparison comparison,
                        String failureReason, String analyzerVersion, Instant requestedAt, Instant completedAt) {
    }

    public record Stored(long id, long submissionId, long fileId, int attemptNumber, String status,
                         AnswerComparison.Result overallResult, List<AnswerComparison.Item> comparisons,
                         String failureReason, Map<String, String> entered, long scholarId,
                         Map<String, Object> currentAnswers, Instant requestedAt, Instant completedAt) {
    }
}
