package kr.co.ktscholarship.backend.scholar;

import java.time.Instant;

public record ScholarUpdateResponse(long id, Instant updatedAt) {
}
