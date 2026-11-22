package kr.co.ktscholarship.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Swagger 묶음: 화면 흐름 순서 9개, 모든 API가 그중 정확히 하나에 들어간다 (클래스 이름 묶음 "...-controller"가 생기지 않게) */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiTagsTest {

    private static final List<String> TAGS = List.of(
        OpenApiConfig.TAG_AUTH, OpenApiConfig.TAG_DASHBOARD, OpenApiConfig.TAG_REQUEST, OpenApiConfig.TAG_REVIEW,
        OpenApiConfig.TAG_SCHOLAR, OpenApiConfig.TAG_MY_SUBMISSION, OpenApiConfig.TAG_FILE, OpenApiConfig.TAG_ANALYSIS,
        OpenApiConfig.TAG_REFERENCE);

    @Autowired
    private WebApplicationContext context;

    @Test
    void everyApiBelongsToOneOfNineOrderedTags() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode doc = JsonMapper.builder().build().readTree(body);

        List<String> declared = new ArrayList<>();
        doc.path("tags").forEach(tag -> {
            declared.add(tag.path("name").asString());
            assertThat(tag.path("description").asString()).isNotBlank();
        });
        assertThat(declared).containsExactlyElementsOf(TAGS);

        List<String> untagged = new ArrayList<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            // 다른 테스트가 권한 확인용으로만 띄우는 TestOnlyController는 문서 대상이 아니다
            if (path.getKey().endsWith("/test-only")) {
                return;
            }
            JsonNode tags = operation.getValue().path("tags");
            if (tags.size() != 1 || !TAGS.contains(tags.get(0).asString())) {
                untagged.add(operation.getKey().toUpperCase() + " " + path.getKey() + " " + tags);
            }
        }));
        assertThat(untagged).isEmpty();
        assertThat(doc.path("paths").size()).isGreaterThan(30);
    }
}
