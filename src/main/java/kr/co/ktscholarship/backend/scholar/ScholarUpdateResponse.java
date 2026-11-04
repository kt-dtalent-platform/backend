package kr.co.ktscholarship.backend.scholar;

import java.time.LocalDateTime;

public record ScholarUpdateResponse(long id, LocalDateTime updatedAt) {
}
