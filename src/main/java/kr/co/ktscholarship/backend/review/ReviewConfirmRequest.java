package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(name = "ReviewConfirmRequest")
public record ReviewConfirmRequest(
    @Schema(description = "제출 상세에서 받은 version. 그사이 바뀌었으면 409") @NotNull @PositiveOrZero Integer version
) {
}
