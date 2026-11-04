package kr.co.ktscholarship.backend.scholar;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;

@Schema(description = "전달한 문자열만 수정합니다. status 변경과 명시적 null은 아직 지원하지 않습니다.")
public record ScholarUpdateRequest(
    @Size(min = 1, max = 50) String name,
    @Size(max = 20) String phone,
    @Size(min = 1, max = 100) String university,
    @Size(max = 100) String major
) {
    private static final Set<String> FIELDS = Set.of("name", "phone", "university", "major");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ScholarUpdateRequest from(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty() || fields.entrySet().stream()
            .anyMatch(entry -> !FIELDS.contains(entry.getKey()) || !(entry.getValue() instanceof String))) {
            throw new IllegalArgumentException("수정할 필드의 문자열 값을 전달해야 합니다.");
        }
        return new ScholarUpdateRequest(
            (String) fields.get("name"), (String) fields.get("phone"),
            (String) fields.get("university"), (String) fields.get("major")
        );
    }
}
