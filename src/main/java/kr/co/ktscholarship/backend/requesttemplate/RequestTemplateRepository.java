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
        // 사용 중인 템플릿만 보여 준다. 요청 저장도 사용 중인 템플릿만 받아서(RequestWriteRepository) 고를 수 없는 유형이 보이지 않게
        var items = jdbc.sql("SELECT id, code, name, default_fields FROM request_templates WHERE is_active = TRUE")
            .query((row, index) -> new RequestTemplateListResponse.Item(
                row.getLong("id"), row.getString("code"), row.getString("name"),
                Objects.requireNonNull(mapper.readValue(row.getString("default_fields"), FIELDS_TYPE))
            )).list();
        return new RequestTemplateListResponse(items);
    }
}
