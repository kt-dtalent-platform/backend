package kr.co.ktscholarship.backend.auth.dto;

import java.time.Instant;

public record PasswordChangeResponse(Instant passwordChangedAt) {
}
