package kr.co.ktscholarship.backend.file;

import java.time.Instant;

public record FilePresignResponse(long fileId, String uploadUrl, Instant expiresAt) {
    @Override
    public String toString() {
        return "FilePresignResponse[fileId=" + fileId + ", uploadUrl=****, expiresAt=" + expiresAt + "]";
    }
}
