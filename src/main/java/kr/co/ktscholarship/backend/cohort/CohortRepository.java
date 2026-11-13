package kr.co.ktscholarship.backend.cohort;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CohortRepository {

    private final JdbcClient jdbc;

    public CohortRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsProgram(long programId) {
        return jdbc.sql("SELECT COUNT(*) FROM programs WHERE id = :programId")
                .param("programId", programId)
                .query(Long.class).single() > 0;
    }

    public List<CohortItem> findByProgramId(long programId) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.started_on AS startedOn, c.ended_on AS endedOn,
                        COUNT(u.id) AS scholarCount
                FROM cohorts c
                LEFT JOIN scholar_profiles s ON s.cohort_id = c.id
                LEFT JOIN users u ON u.id = s.user_id AND u.deleted_at IS NULL AND u.role = 'SCHOLAR'
                WHERE c.program_id = :programId
                GROUP BY c.id, c.name, c.started_on, c.ended_on
                ORDER BY c.id ASC
                """)
                .param("programId", programId)
                .query(CohortItem.class).list();
    }
}
