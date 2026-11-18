package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "RequestPublishResponse")
public record RequestPublishResponse(
    long id,
    RequestStatus status,
    @Schema(description = "공개 시각 (UTC, 끝에 Z)") Instant publishedAt,
    @Schema(description = "공개 순간 대상으로 확정된 장학생 수") int targetCount
) {
}
