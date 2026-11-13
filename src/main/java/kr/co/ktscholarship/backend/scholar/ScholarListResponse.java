package kr.co.ktscholarship.backend.scholar;

import java.util.List;

public record ScholarListResponse(
    List<Item> items, int page, int pageSize, long total
) {
    public record Item(
        long id, String name, String email, String maskedPhone, String cohortName,
        String university, String status, String accountStatus
    ) {
    }
}
