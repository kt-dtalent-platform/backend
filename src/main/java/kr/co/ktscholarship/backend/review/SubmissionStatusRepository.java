package kr.co.ktscholarship.backend.review;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import kr.co.ktscholarship.backend.request.RequestStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SubmissionStatusRepository {

    private static final TypeReference<Map<String, Object>> ANSWERS = new TypeReference<>() {};

    /*
     * 대상자(request_targets)를 기준으로 제출을 붙인다. 제출 행이 없거나 작성 중이면 미제출.
     * request_targets·submissions 모두 (request_id, scholar_id)가 UNIQUE라 한 사람이 두 줄이 되지 않는다.
     */
    private static final String FROM_SQL = """
        FROM request_targets t
        JOIN users u ON u.id = t.scholar_id
        LEFT JOIN scholar_profiles p ON p.user_id = u.id
        LEFT JOIN cohorts c ON c.id = p.cohort_id
        LEFT JOIN submissions s ON s.request_id = t.request_id AND s.scholar_id = t.scholar_id
        WHERE t.request_id = :requestId
        """;

    // 화면의 상태 칩 4개
    private static final String BUCKET_SQL = """
        CASE WHEN s.status IN ('SUBMITTED', 'RESUBMITTED') THEN 'WAITING'
             WHEN s.status = 'REVISION_REQUIRED' THEN 'REVISION_REQUIRED'
             WHEN s.status = 'CONFIRMED' THEN 'CONFIRMED'
             ELSE 'NOT_SUBMITTED' END
        """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SubmissionStatusRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<SubmissionStatusResponse.RequestInfo> findRequest(long requestId) {
        return jdbc.sql("""
            SELECT r.id, r.title, c.name AS cohort_name, r.closes_at, r.status
            FROM requests r LEFT JOIN cohorts c ON c.id = r.cohort_id
            WHERE r.id = :id
            """)
            .param("id", requestId)
            .query((row, index) -> new SubmissionStatusResponse.RequestInfo(
                row.getLong("id"), row.getString("title"), row.getString("cohort_name"),
                UtcDateTimes.fromDb(row.getObject("closes_at", LocalDateTime.class)),
                RequestStatus.valueOf(row.getString("status"))))
            .optional();
    }

    public SubmissionStatusResponse.Summary summary(long requestId) {
        return jdbc.sql("SELECT " + BUCKET_SQL + " AS bucket, COUNT(*) AS cnt " + FROM_SQL + " GROUP BY " + BUCKET_SQL)
            .param("requestId", requestId)
            .query((row, index) -> Map.entry(row.getString("bucket"), row.getLong("cnt")))
            .list()
            .stream()
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue),
                counts -> {
                    long waiting = counts.getOrDefault("WAITING", 0L);
                    long revision = counts.getOrDefault("REVISION_REQUIRED", 0L);
                    long confirmed = counts.getOrDefault("CONFIRMED", 0L);
                    long notSubmitted = counts.getOrDefault("NOT_SUBMITTED", 0L);
                    return new SubmissionStatusResponse.Summary(
                        waiting + revision + confirmed + notSubmitted, notSubmitted, waiting, revision, confirmed);
                }));
    }

    /**
     * 기본 순서: 검토 대기(오래 기다린 순) → 보완 필요 → 미제출 → 확인 완료, 같은 칸 안에서는 이름순.
     * 재제출은 마지막으로 바뀐 시각(updated_at)부터 기다린 것으로 본다.
     */
    public List<SubmissionStatusResponse.Item> findItems(long requestId, String bucket, String keyword,
                                                         int page, int pageSize) {
        MapSqlParameterSource params = filterParams(requestId, bucket, keyword)
            .addValue("limit", pageSize)
            .addValue("offset", (long) (page - 1) * pageSize);
        return query(params, bucket, keyword, "LIMIT :limit OFFSET :offset");
    }

    /** 조건에 맞는 대상자 전부(기본 순서). 열 정렬은 서비스에서 이 목록을 정렬해 자른다 */
    public List<SubmissionStatusResponse.Item> findAllItems(long requestId, String bucket, String keyword) {
        return query(filterParams(requestId, bucket, keyword), bucket, keyword, "");
    }

    private List<SubmissionStatusResponse.Item> query(MapSqlParameterSource params, String bucket, String keyword,
                                                      String limit) {
        return jdbc.sql("""
            SELECT u.id AS scholar_id, u.name AS scholar_name, c.name AS cohort_name, p.school_name,
                s.id AS submission_id, s.status, s.answers, s.submitted_at, s.is_late
            """ + FROM_SQL + filterSql(bucket, keyword) + """
            ORDER BY CASE %s WHEN 'WAITING' THEN 0 WHEN 'REVISION_REQUIRED' THEN 1
                     WHEN 'NOT_SUBMITTED' THEN 2 ELSE 3 END,
                CASE WHEN s.status = 'RESUBMITTED' THEN s.updated_at
                     WHEN s.status = 'SUBMITTED' THEN s.submitted_at END,
                u.name, u.id
            %s
            """.formatted(BUCKET_SQL, limit))
            .paramSource(params)
            .query((row, index) -> toItem(
                row.getLong("scholar_id"), row.getString("scholar_name"), row.getString("cohort_name"),
                row.getString("school_name"), row.getObject("submission_id", Long.class), row.getString("status"),
                row.getString("answers"), row.getObject("submitted_at", LocalDateTime.class), row.getBoolean("is_late")))
            .list();
    }

    public long count(long requestId, String bucket, String keyword) {
        Long total = jdbc.sql("SELECT COUNT(*) " + FROM_SQL + filterSql(bucket, keyword))
            .paramSource(filterParams(requestId, bucket, keyword))
            .query(Long.class)
            .single();
        return total == null ? 0 : total;
    }

    private String filterSql(String bucket, String keyword) {
        String sql = "";
        if (bucket != null) {
            sql += " AND " + BUCKET_SQL + " = :bucket";
        }
        if (keyword != null) {
            // LIKE 대신 LOCATE: 검색어의 %·_를 글자 그대로 찾는다
            sql += " AND LOCATE(:keyword, u.name) > 0";
        }
        return sql + "\n";
    }

    private MapSqlParameterSource filterParams(long requestId, String bucket, String keyword) {
        MapSqlParameterSource params = new MapSqlParameterSource("requestId", requestId);
        if (bucket != null) {
            params.addValue("bucket", bucket);
        }
        if (keyword != null) {
            params.addValue("keyword", keyword);
        }
        return params;
    }

    private SubmissionStatusResponse.Item toItem(long scholarId, String name, String cohort, String school,
                                                 Long submissionId, String status, String answersJson,
                                                 LocalDateTime submittedAt, boolean late) {
        // 작성 중은 아직 내지 않은 것이라 담당자에게는 미제출로 보이고 제출 id도 주지 않는다
        boolean notSubmitted = status == null || status.equals("DRAFT");
        Map<String, Object> answers = notSubmitted ? Map.of() : parse(answersJson);
        return new SubmissionStatusResponse.Item(
            scholarId, name, cohort, school,
            answers.get("gpa") instanceof Number gpa ? gpa.doubleValue() : null,
            answers.get("gpa_scale") == null ? null : String.valueOf(answers.get("gpa_scale")),
            notSubmitted ? null : submissionId,
            notSubmitted ? "NOT_SUBMITTED" : status,
            notSubmitted ? null : UtcDateTimes.fromDb(submittedAt),
            !notSubmitted && late,
            false);
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
}
