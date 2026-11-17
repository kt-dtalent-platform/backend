package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@Schema(name = "ReviewRevisionRequest")
public record ReviewRevisionRequest(
    @Schema(description = "다시 받을 항목 키. 이 요청에 있는 항목이어야 함")
    @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> fieldKeys,
    @Schema(description = "장학생에게 보이는 보완 사유") @NotBlank @Size(max = 1000) String reason,
    @Schema(description = "보완 기한 (UTC, 끝에 Z). 장학생별 연장 마감 기능이 들어오면 재제출 마감으로 연결합니다. 지금은 형식만 검사합니다")
    Instant dueAt,
    @Schema(description = "제출 상세에서 받은 version. 그사이 바뀌었으면 409") @NotNull @PositiveOrZero Integer version
) {
}
