package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 요청 만들기와 작성 중인 요청 고치기가 같이 쓰는 본문. 프론트 폼 전체를 그대로 받는다.
 * 여기서는 본문만 보고 알 수 있는 형식만 검사하고, DB나 현재 시각이 필요한 검사는 RequestService가 한다.
 */
@Schema(name = "RequestSaveRequest")
public record RequestSaveRequest(
    @NotNull @Positive Long programId,
    @Schema(description = "null이면 프로그램 전체 대상(전체 기수)") @Positive Long cohortId,
    @NotNull @Positive Long templateId,
    @NotBlank @Size(max = 200) String title,
    @Schema(description = "제출 안내") @Size(max = 2000) String description,
    @NotNull @Min(2000) @Max(2100) Integer academicYear,
    @Schema(allowableValues = {"1", "2", "SUMMER", "WINTER"})
    @NotNull @Pattern(regexp = "1|2|SUMMER|WINTER") String semester,
    @Schema(description = "UTC, 끝에 Z. 지금보다 뒤여야 합니다.", example = "2026-12-23T14:59:00Z")
    @NotNull Instant closesAt,
    @Schema(description = "제출 완료 안내") @Size(max = 2000) String completionMessage
) {
}
