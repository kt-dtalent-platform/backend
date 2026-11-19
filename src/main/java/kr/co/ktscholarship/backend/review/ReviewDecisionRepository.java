package kr.co.ktscholarship.backend.review;

import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReviewDecisionRepository {

    private static final TypeReference<Map<String, Object>> ANSWERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String jsonParameter;

    public ReviewDecisionRepository(JdbcClient jdbc, JsonMapper json, DataSource dataSource)
        throws MetaDataAccessException {
        this.jdbc = jdbc;
        this.json = json;
        // H2(테스트)는 VARCHAR를 JSON으로 CAST하면 객체가 아닌 JSON 문자열로 저장해서 표기를 바꾼다
        String database = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        this.jsonParameter = "H2".equals(database) ? ":snapshot FORMAT JSON" : "CAST(:snapshot AS JSON)";
    }

    /**
     * 검토할 제출을 잠그고 읽는다. 담당자가 판단하는 사이 장학생의 재제출이나 다른 담당자의 검토가
     * 끼어들지 않도록, 확인부터 저장까지 같은 트랜잭션에서 이 잠금을 쥐고 있는다.
     */
    public Optional<Target> lock(long submissionId) {
        return jdbc.sql("""
            SELECT id, request_id, status, version, answers FROM submissions WHERE id = :id FOR UPDATE
            """)
            .param("id", submissionId)
            .query((row, index) -> new Target(
                row.getLong("id"), row.getLong("request_id"), row.getString("status"), row.getInt("version"),
                parse(row.getString("answers"))))
            .optional();
    }

    public Set<String> findFieldKeys(long requestId) {
        return jdbc.sql("SELECT field_key FROM request_fields WHERE request_id = :id")
            .param("id", requestId)
            .query(String.class)
            .set();
    }

    /** 잠근 상태에서 version을 확인했으므로 조건은 보조 안전장치. 바꾼 줄 수를 돌려준다 */
    public int confirm(Target target, long managerId, Instant now) {
        return jdbc.sql("""
            UPDATE submissions
            SET status = 'CONFIRMED', confirmed_at = :now, confirmed_by = :manager,
                version = version + 1, updated_at = :now
            WHERE id = :id AND version = :version AND status = :status
            """)
            .param("now", UtcDateTimes.toDb(now))
            .param("manager", managerId)
            .param("id", target.id())
            .param("version", target.version())
            .param("status", target.status())
            .update();
    }

    public int markRevisionRequired(Target target, Instant now) {
        return jdbc.sql("""
            UPDATE submissions
            SET status = 'REVISION_REQUIRED', version = version + 1, updated_at = :now
            WHERE id = :id AND version = :version AND status = :status
            """)
            .param("now", UtcDateTimes.toDb(now))
            .param("id", target.id())
            .param("version", target.version())
            .param("status", target.status())
            .update();
    }

    /** 보완 요청과 항목을 저장한다. 장학생의 재제출은 가장 최근의 해결 안 된 보완 요청을 읽는다 */
    public long insertRevision(long submissionId, List<String> fieldKeys, String reason, long managerId, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO revision_requests (submission_id, reason, requested_by, requested_at)
            VALUES (:submission, :reason, :manager, :now)
            """)
            .param("submission", submissionId)
            .param("reason", reason)
            .param("manager", managerId)
            .param("now", UtcDateTimes.toDb(now))
            .update(key, "id");
        long revisionId = Objects.requireNonNull(key.getKey()).longValue();
        for (String fieldKey : fieldKeys) {
            jdbc.sql("INSERT INTO revision_request_fields (revision_request_id, field_key) VALUES (:revision, :field)")
                .param("revision", revisionId)
                .param("field", fieldKey)
                .update();
        }
        return revisionId;
    }

    /** 상태 이력. 스냅샷 모양은 장학생 제출 이력과 같은 {answers, files} */
    public void history(Target target, String to, long managerId, Instant now) {
        List<Map<String, Object>> files = jdbc.sql("""
            SELECT id, field_key, original_name, size_bytes, upload_status FROM submission_files
            WHERE submission_id = :id AND upload_status <> 'DELETED' ORDER BY id
            """)
            .param("id", target.id())
            .query((row, index) -> {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("id", row.getLong("id"));
                file.put("fieldKey", row.getString("field_key"));
                file.put("originalName", row.getString("original_name"));
                file.put("sizeBytes", row.getLong("size_bytes"));
                file.put("uploadStatus", row.getString("upload_status"));
                return file;
            })
            .list();
        jdbc.sql("""
            INSERT INTO submission_histories (submission_id, from_status, to_status, actor_type, actor_id, snapshot, changed_at)
            VALUES (:id, :from, :to, 'MANAGER', :manager, %s, :now)
            """.formatted(jsonParameter))
            .param("id", target.id())
            .param("from", target.status())
            .param("to", to)
            .param("manager", managerId)
            .param("snapshot", json.writeValueAsString(Map.of("answers", target.answers(), "files", files)))
            .param("now", UtcDateTimes.toDb(now))
            .update();
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

    public record Target(long id, long requestId, String status, int version, Map<String, Object> answers) {
    }

    static Set<String> duplicates(List<String> values) {
        return values.stream()
            .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
            .entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
}
