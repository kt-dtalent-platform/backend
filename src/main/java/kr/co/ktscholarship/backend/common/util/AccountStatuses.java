package kr.co.ktscholarship.backend.common.util;

import java.time.Instant;

/** AuthService와 같은 기준으로 만료된 잠금을 조회·발송 판단에 반영한다. DB 상태는 변경하지 않는다. */
public final class AccountStatuses {
    private AccountStatuses() {}

    public static String effective(String status, Instant lockedUntil, Instant now) {
        return "LOCKED".equals(status) && (lockedUntil == null || !lockedUntil.isAfter(now)) ? "ACTIVE" : status;
    }
}
