package kr.co.ktscholarship.backend.request;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RequestWriteRepository {

    private final JdbcClient jdbc;

    public RequestWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean programExists(long programId) {
        return jdbc.sql("SELECT COUNT(*) FROM programs WHERE id = :id")
            .param("id", programId)
            .query(Long.class)
            .single() > 0;
    }

    // 기수가 어느 프로그램 소속인지. 기수가 없으면 빈 Optional
    public Optional<Long> findCohortProgramId(long cohortId) {
        return jdbc.sql("SELECT program_id FROM cohorts WHERE id = :id")
            .param("id", cohortId)
            .query(Long.class)
            .optional();
    }

    public boolean activeTemplateExists(long templateId) {
        return jdbc.sql("SELECT COUNT(*) FROM request_templates WHERE id = :id AND is_active = TRUE")
            .param("id", templateId)
            .query(Long.class)
            .single() > 0;
    }

    public Optional<RequestStatus> findStatus(long requestId) {
        return jdbc.sql("SELECT status FROM requests WHERE id = :id")
            .param("id", requestId)
            .query((row, index) -> RequestStatus.valueOf(row.getString("status")))
            .optional();
    }

    public long insertDraft(RequestSaveRequest body, long createdBy, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO requests (program_id, cohort_id, template_id, title, description, academic_year, semester,
                closes_at, status, completion_message, created_by, created_at, updated_at)
            VALUES (:programId, :cohortId, :templateId, :title, :description, :academicYear, :semester,
                :closesAt, 'DRAFT', :completionMessage, :createdBy, :now, :now)
            """)
            .param("programId", body.programId())
            .param("cohortId", body.cohortId())
            .param("templateId", body.templateId())
            .param("title", body.title())
            .param("description", body.description())
            .param("academicYear", body.academicYear())
            .param("semester", body.semester())
            .param("closesAt", UtcDateTimes.toDb(body.closesAt().truncatedTo(ChronoUnit.SECONDS)))
            .param("completionMessage", body.completionMessage())
            .param("createdBy", createdBy)
            .param("now", UtcDateTimes.toDb(now))
            .update(keyHolder, "id");
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 작성 중일 때만 고친다. 상태 확인과 수정을 UPDATE 한 번에 묶어서,
     * 같은 순간 다른 담당자가 공개해도 공개된 요청이 고쳐지지 않는다. 고친 줄 수를 돌려준다(0 또는 1).
     */
    public int updateDraft(long requestId, RequestSaveRequest body, Instant now) {
        return jdbc.sql("""
            UPDATE requests
            SET program_id = :programId, cohort_id = :cohortId, template_id = :templateId, title = :title,
                description = :description, academic_year = :academicYear, semester = :semester,
                closes_at = :closesAt, completion_message = :completionMessage,
                version = version + 1, updated_at = :now
            WHERE id = :id AND status = 'DRAFT'
            """)
            .param("programId", body.programId())
            .param("cohortId", body.cohortId())
            .param("templateId", body.templateId())
            .param("title", body.title())
            .param("description", body.description())
            .param("academicYear", body.academicYear())
            .param("semester", body.semester())
            .param("closesAt", UtcDateTimes.toDb(body.closesAt().truncatedTo(ChronoUnit.SECONDS)))
            .param("completionMessage", body.completionMessage())
            .param("now", UtcDateTimes.toDb(now))
            .param("id", requestId)
            .update();
    }
}
