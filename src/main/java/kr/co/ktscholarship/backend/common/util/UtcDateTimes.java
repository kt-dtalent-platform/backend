package kr.co.ktscholarship.backend.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JdbcClient로 DATETIME 칸을 읽고 쓸 때 쓰는 변환.
 * DB 칸은 UTC 기준 값으로 약속하고, 앱과 API 응답에서는 Instant(Z)로 다룬다.
 * Instant를 JDBC에 그대로 넘기면 드라이버가 JVM 시간대로 바꿀 수 있어서 경계에서 직접 변환한다.
 */
public final class UtcDateTimes {

    private UtcDateTimes() {
    }

    // 앱의 Instant -> DB에 넣을 UTC 기준 값
    public static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // DB에서 읽은 UTC 기준 값 -> 앱의 Instant
    public static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
