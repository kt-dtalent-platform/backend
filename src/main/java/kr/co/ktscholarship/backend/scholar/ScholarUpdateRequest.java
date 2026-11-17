package kr.co.ktscholarship.backend.scholar;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.Set;

@Schema(description = "전달한 필드만 수정합니다. 학교 변경 시 university를 보내고, 목록에서 고르면 schoolId도 보냅니다. 직접 입력은 schoolId가 null 또는 생략입니다.")
public record ScholarUpdateRequest(
    @Size(min = 1, max = 50) String name,
    @Size(max = 20) String phone,
    @Size(min = 1, max = 100) String university,
    @Size(max = 100) String major,
    @Positive Long schoolId
) {
    private static final Set<String> FIELDS = Set.of("name", "phone", "university", "major", "schoolId");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ScholarUpdateRequest from(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty() || fields.entrySet().stream()
            .anyMatch(entry -> !FIELDS.contains(entry.getKey())
                || (!entry.getKey().equals("schoolId") && !(entry.getValue() instanceof String)))) {
            throw new IllegalArgumentException("수정할 필드의 문자열 값을 전달해야 합니다.");
        }
        Object schoolId = fields.get("schoolId");
        if ((schoolId != null && !(schoolId instanceof Integer) && !(schoolId instanceof Long))
            || (fields.containsKey("schoolId") && !fields.containsKey("university"))
            || (fields.get("university") instanceof String name && name.isBlank())) {
            throw new IllegalArgumentException("학교 변경은 university와 정수 schoolId 또는 null을 전달해야 합니다.");
        }
        return new ScholarUpdateRequest(
            (String) fields.get("name"), (String) fields.get("phone"),
            (String) fields.get("university"), (String) fields.get("major"),
            schoolId == null ? null : ((Number) schoolId).longValue()
        );
    }
}
