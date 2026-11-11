package kr.co.ktscholarship.backend.requesttemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RequestTemplateRepository {

    private static final TypeReference<List<Map<String, Object>>> FIELDS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public RequestTemplateRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public RequestTemplateListResponse findAll() {
        var items = jdbc.sql("SELECT id, code, name, default_fields FROM request_templates")
            .query((row, index) -> new RequestTemplateListResponse.Item(
                row.getLong("id"), row.getString("code"), row.getString("name"),
                Objects.requireNonNull(mapper.readValue(row.getString("default_fields"), FIELDS_TYPE))
            )).list();
        return new RequestTemplateListResponse(items);
    }
}
