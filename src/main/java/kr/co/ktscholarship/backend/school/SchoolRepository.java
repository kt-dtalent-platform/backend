package kr.co.ktscholarship.backend.school;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SchoolRepository {
    private final JdbcClient jdbc;
    public SchoolRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<SchoolSearchResponse.Item> search(String keyword, int size) {
        String normalized = keyword.replace(" ", "").replace("(", "").replace(")", "").toLowerCase(Locale.ROOT);
        return jdbc.sql("""
            SELECT id, name, region FROM schools
            WHERE :keyword <> '' AND LOCATE(:keyword, LOWER(REPLACE(REPLACE(REPLACE(name, ' ', ''), '(', ''), ')', ''))) > 0
            ORDER BY CASE
                WHEN LOWER(REPLACE(REPLACE(REPLACE(name, ' ', ''), '(', ''), ')', '')) = :keyword THEN 0
                WHEN LOCATE(:keyword, LOWER(REPLACE(REPLACE(REPLACE(name, ' ', ''), '(', ''), ')', ''))) = 1 THEN 1
                ELSE 2 END, name, id
            LIMIT :size
            """).param("keyword", normalized).param("size", size)
            .query((rs, n) -> new SchoolSearchResponse.Item(rs.getLong("id"), rs.getString("name"), rs.getString("region"))).list();
    }
}
