package kr.co.ktscholarship.backend.file;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FilePresignRequest(
    @NotBlank @Size(max = 64) String fieldKey,
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Size(max = 100) String contentType,
    @NotNull @Positive Long size
) {
    @JsonCreator
    public static FilePresignRequest from(
        @JsonProperty("fieldKey") String fieldKey,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("contentType") String contentType,
        @JsonProperty("size") Object size
    ) {
        // 소수·문자열이 파일 크기 정수로 강제 변환되지 않도록 확인합니다.
        if (size != null && !(size instanceof Integer) && !(size instanceof Long)) {
            throw new IllegalArgumentException("size는 정수여야 합니다.");
        }
        return new FilePresignRequest(fieldKey, fileName, contentType,
            size == null ? null : ((Number) size).longValue());
    }
}
