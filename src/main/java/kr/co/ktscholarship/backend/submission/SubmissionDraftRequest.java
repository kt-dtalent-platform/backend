package kr.co.ktscholarship.backend.submission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

public record SubmissionDraftRequest(
    @NotNull Map<String, Object> answers,
    @NotNull @PositiveOrZero Integer version
) {
    @JsonCreator
    public static SubmissionDraftRequest from(
        @JsonProperty("answers") Map<String, Object> answers,
        @JsonProperty("version") Object version
    ) {
        // 소수 버전이 정수로 잘려 변환되지 않도록 원본 JSON 타입을 확인합니다.
        if (version != null && !(version instanceof Integer)) {
            throw new IllegalArgumentException("version은 정수여야 합니다.");
        }
        return new SubmissionDraftRequest(answers, (Integer) version);
    }
}
