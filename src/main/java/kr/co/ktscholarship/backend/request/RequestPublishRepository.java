package kr.co.ktscholarship.backend.request;

import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;

@Repository
public class RequestPublishRepository {

    private final JdbcClient jdbc;
    private final boolean h2;

    public RequestPublishRepository(JdbcClient jdbc, DataSource dataSource) throws MetaDataAccessException {
        this.jdbc = jdbc;
        // H2(테스트)는 VARCHAR를 JSON으로 CAST하면 객체가 아닌 JSON 문자열로 저장해서 표기를 바꾼다
        this.h2 = "H2".equals(JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName));
    }

    /**
     * 공개할 요청을 잠그고 읽는다. 같은 요청을 두 사람이 동시에 공개하면
     * 뒤에 온 쪽은 앞 트랜잭션이 끝날 때까지 기다렸다가 바뀐 상태(IN_PROGRESS)를 본다.
     */
    public Optional<Target> lockForPublish(long requestId) {
        return jdbc.sql("""
            SELECT status, program_id, cohort_id, template_id, closes_at
            FROM requests WHERE id = :id FOR UPDATE
            """)
            .param("id", requestId)
            .query((row, index) -> new Target(
                RequestStatus.valueOf(row.getString("status")),
                row.getLong("program_id"),
                row.getObject("cohort_id", Long.class),
                row.getLong("template_id"),
                UtcDateTimes.fromDb(row.getObject("closes_at", LocalDateTime.class))))
            .optional();
    }

    public Optional<String> findTemplateFields(long templateId) {
        return jdbc.sql("SELECT default_fields FROM request_templates WHERE id = :id")
            .param("id", templateId)
            .query(String.class)
            .optional();
    }

    public void insertFields(long requestId, List<RequestField> fields) {
        for (int i = 0; i < fields.size(); i++) {
            RequestField field = fields.get(i);
            var statement = jdbc.sql("""
                INSERT INTO request_fields (request_id, field_key, label, field_type, required,
                    options, validation, help_text, sort_order)
                VALUES (:requestId, :fieldKey, :label, :fieldType, :required, %s, %s, :helpText, :sortOrder)
                """.formatted(json("options", field.optionsJson()), json("validation", field.validationJson())))
                .param("requestId", requestId)
                .param("fieldKey", field.fieldKey())
                .param("label", field.label())
                .param("fieldType", field.fieldType())
                .param("required", field.required())
                .param("helpText", field.helpText())
                .param("sortOrder", i + 1);
            if (field.optionsJson() != null) {
                statement = statement.param("options", field.optionsJson());
            }
            if (field.validationJson() != null) {
                statement = statement.param("validation", field.validationJson());
            }
            statement.update();
        }
    }

    /**
     * 지금 이 순간의 대상 장학생을 request_targets에 복사한다(스냅샷). 넣은 인원을 돌려준다.
     * 기수를 지정했으면 그 기수, 아니면 프로그램의 모든 기수.
     * 재학 중이고 비활성·삭제되지 않은 장학생 계정만 넣는다 (잠금은 잠깐이라 포함).
     */
    public int insertTargets(long requestId, long programId, Long cohortId, Instant now) {
        String cohortCondition = cohortId == null ? "" : " AND sp.cohort_id = :cohortId";
        var statement = jdbc.sql("""
            INSERT INTO request_targets (request_id, scholar_id, assigned_at)
            SELECT :requestId, sp.user_id, :now
            FROM scholar_profiles sp
            JOIN cohorts c ON c.id = sp.cohort_id
            JOIN users u ON u.id = sp.user_id
            WHERE c.program_id = :programId
              AND sp.status = 'ENROLLED'
              AND u.role = 'SCHOLAR'
              AND u.status <> 'INACTIVE'
              AND u.deleted_at IS NULL
            """ + cohortCondition)
            .param("requestId", requestId)
            .param("now", UtcDateTimes.toDb(now))
            .param("programId", programId);
        if (cohortId != null) {
            statement = statement.param("cohortId", cohortId);
        }
        return statement.update();
    }

    /** 작성 중일 때만 공개로 바꾼다. 바꾼 줄 수를 돌려준다(0 또는 1). */
    public int markPublished(long requestId, Instant now) {
        return jdbc.sql("""
            UPDATE requests
            SET status = 'IN_PROGRESS', published_at = :now, version = version + 1, updated_at = :now
            WHERE id = :id AND status = 'DRAFT'
            """)
            .param("now", UtcDateTimes.toDb(now))
            .param("id", requestId)
            .update();
    }

    // 값이 없으면 NULL, 있으면 DB에 맞는 JSON 표기
    private String json(String name, String value) {
        if (value == null) {
            return "NULL";
        }
        return h2 ? ":" + name + " FORMAT JSON" : "CAST(:" + name + " AS JSON)";
    }

    public record Target(RequestStatus status, long programId, Long cohortId, long templateId, Instant closesAt) {
    }

    /** 템플릿 JSON에서 꺼낸 항목 하나. options·validation은 JSON 문자열(없으면 null) */
    public record RequestField(String fieldKey, String label, String fieldType, boolean required,
                               String optionsJson, String validationJson, String helpText) {
    }
}
