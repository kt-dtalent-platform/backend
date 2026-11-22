package kr.co.ktscholarship.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** V11 성적증명서 템플릿: 공개 때 복사·제출 검증·파일 업로드·문서 분석이 쓰는 키와 규칙이 모두 들어 있는가 */
@SpringBootTest
@ActiveProfiles("test")
class MigrationV11Test {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void gradeCertificateTemplateMatchesAnalysisKeysAndUploadRules() {
        String json = jdbc.sql("SELECT default_fields FROM request_templates WHERE code = 'GRADE_CERTIFICATE'")
            .query(String.class).single();
        List<Map<String, Object>> fields = JsonMapper.builder().build().readValue(json, new TypeReference<>() {});

        assertThat(fields).extracting(f -> f.get("fieldKey"))
            .containsExactly("gpa", "gpa_scale", "credits", "transcript", "note");
        assertThat(fields).extracting(f -> f.get("required")).containsExactly(true, true, true, true, false);
        assertThat(fields.get(2).get("label")).isEqualTo("취득 학점 (F 제외)");

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> file = (Map<String, Object>) fields.get(3).get("validation");
        // 파일 업로드 주소 발급이 요구하는 세 값 (없으면 400)
        assertThat(file).containsKeys("allowedExtensions", "maxSizeMb", "maxFiles");
        assertThat((List) file.get("allowedExtensions")).containsExactly("pdf", "jpg", "jpeg", "png");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scales = (List<Map<String, Object>>) fields.get(1).get("options");
        assertThat(scales).extracting(o -> o.get("value")).containsExactly("4.5", "4.3");
    }
}
