package kr.co.ktscholarship.backend.common.response;

import java.time.Instant;

public record ApiMeta(Instant timestamp, String path) {

    public static ApiMeta from(String path) {
        return new ApiMeta(Instant.now(), path);
    }
}
