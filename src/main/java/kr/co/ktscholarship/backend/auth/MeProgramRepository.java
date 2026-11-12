package kr.co.ktscholarship.backend.auth;

import java.util.List;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MeProgramRepository {

    private final JdbcClient jdbc;

    public MeProgramRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MeResponse.Program> findAll() {
        return jdbc.sql("SELECT id, name FROM programs ORDER BY id")
                .query((row, index) -> new MeResponse.Program(row.getLong("id"), row.getString("name")))
                .list();
    }
}
