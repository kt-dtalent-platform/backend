package kr.co.ktscholarship.backend.request;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestDetailRepository {

    private final JdbcClient jdbc;

    public RequestDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // 있으면 값, 없으면 빈 Optional. 없을 때 어떤 오류로 응답할지는 호출하는 쪽이 정한다
    public Optional<RequestDetailResponse> findById(long requestId) {
        return jdbc.sql("""
            SELECT id, status, program_id, cohort_id, template_id, title, description,
                academic_year, semester, closes_at, completion_message, published_at
            FROM requests
            WHERE id = :id
            """)
            .param("id", requestId)
            .query((row, index) -> new RequestDetailResponse(
                row.getLong("id"),
                RequestStatus.valueOf(row.getString("status")),
                row.getLong("program_id"),
                row.getObject("cohort_id", Long.class),
                row.getLong("template_id"),
                row.getString("title"),
                row.getString("description"),
                row.getInt("academic_year"),
                row.getString("semester"),
                UtcDateTimes.fromDb(row.getObject("closes_at", LocalDateTime.class)),
                row.getString("completion_message"),
                UtcDateTimes.fromDb(row.getObject("published_at", LocalDateTime.class))
            ))
            .optional();
    }
}
