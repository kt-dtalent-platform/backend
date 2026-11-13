package kr.co.ktscholarship.backend.task;

import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record MyTaskResponse(List<Item> items) {
    @Schema(name = "MyTaskItem")
    public record Item(
        long requestId, String title, Instant dueAt, long dDay, String status, String revisionReason
    ) {
    }
}
