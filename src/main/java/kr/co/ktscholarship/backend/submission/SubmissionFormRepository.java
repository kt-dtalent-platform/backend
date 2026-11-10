package kr.co.ktscholarship.backend.submission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.Field;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.File;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.Option;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.Request;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.Revision;
import kr.co.ktscholarship.backend.submission.SubmissionFormData.Submission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionFormRepository {

    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Option>> OPTIONS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public SubmissionFormRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public SubmissionFormData find(long requestId, long scholarId) {
        var request = jdbc.sql("""
            SELECT id, title, description, closes_at, status FROM requests
            WHERE id = :id AND status IN ('IN_PROGRESS', 'CLOSED') AND published_at IS NOT NULL
            """).param("id", requestId)
            .query((row, index) -> new Request(
                row.getLong("id"), row.getString("title"), row.getString("description"),
                row.getObject("closes_at", LocalDateTime.class), row.getString("status")
            )).optional().orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));

        boolean target = Boolean.TRUE.equals(jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM request_targets WHERE request_id = :requestId AND scholar_id = :scholarId
            )
            """).param("requestId", requestId).param("scholarId", scholarId).query(Boolean.class).single());
        if (!target) {
            throw new BusinessException(ErrorCode.NOT_A_TARGET);
        }

        var fields = jdbc.sql("""
            SELECT field_key, label, field_type, required, options, validation, help_text FROM request_fields
            WHERE request_id = :id ORDER BY sort_order
            """).param("id", requestId)
            .query((row, index) -> new Field(
                row.getString("field_key"), row.getString("label"), row.getString("field_type"),
                row.getBoolean("required"), options(row.getString("options")),
                object(row.getString("validation")), row.getString("help_text")
            )).list();

        var submission = jdbc.sql("""
            SELECT id, status, answers, version FROM submissions
            WHERE request_id = :requestId AND scholar_id = :scholarId
            """).param("requestId", requestId).param("scholarId", scholarId)
            .query((row, index) -> new Submission(
                row.getLong("id"), row.getString("status"),
                Objects.requireNonNull(object(row.getString("answers"))), row.getInt("version")
            )).optional().orElse(null);
        if (submission == null) {
            return new SubmissionFormData(request, fields, null, List.of(), null);
        }

        // 노출 대상 상태는 아직 정하지 않고 원본 상태를 내부 모델에 보존합니다. 비공개 저장 키는 조회하지 않습니다.
        var files = jdbc.sql("""
            SELECT id, field_key, original_name, upload_status FROM submission_files WHERE submission_id = :id
            """).param("id", submission.id())
            .query((row, index) -> new File(
                row.getLong("id"), row.getString("field_key"), row.getString("original_name"),
                row.getString("upload_status")
            )).list();

        Revision revision = null;
        if (submission.status().equals("REVISION_REQUIRED")) {
            revision = jdbc.sql("""
                SELECT id, reason FROM revision_requests WHERE submission_id = :id AND resolved_at IS NULL
                ORDER BY requested_at DESC, id DESC LIMIT 1
                """).param("id", submission.id())
                .query((row, index) -> new Revision(row.getLong("id"), row.getString("reason"), List.of()))
                .optional().orElse(null);
            if (revision != null) {
                var keys = jdbc.sql("SELECT field_key FROM revision_request_fields WHERE revision_request_id = :id")
                    .param("id", revision.id()).query(String.class).list();
                revision = new Revision(revision.id(), revision.reason(), keys);
            }
        }
        return new SubmissionFormData(request, fields, submission, files, revision);
    }

    private Map<String, Object> object(String json) {
        return json == null ? null : mapper.readValue(json, OBJECT_TYPE);
    }

    private List<Option> options(String json) {
        return json == null ? null : mapper.readValue(json, OPTIONS_TYPE);
    }
}
