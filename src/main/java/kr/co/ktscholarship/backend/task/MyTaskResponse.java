package kr.co.ktscholarship.backend.task;

import java.time.LocalDateTime;
import java.util.List;

public record MyTaskResponse(List<Item> items) {
    public record Item(
        long requestId, String title, LocalDateTime dueAt, long dDay, String status, String revisionReason
    ) {
    }
}
