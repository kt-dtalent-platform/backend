package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ReviewDecisionResponse")
public record ReviewDecisionResponse(
    long id,
    @Schema(allowableValues = {"CONFIRMED", "REVISION_REQUIRED"}) String status,
    @Schema(description = "바뀐 뒤의 version") int version
) {
}
