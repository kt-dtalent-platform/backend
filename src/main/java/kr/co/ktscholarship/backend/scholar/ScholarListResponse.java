package kr.co.ktscholarship.backend.scholar;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record ScholarListResponse(
    List<Item> items, int page, int pageSize, long total
) {
    @Schema(name = "ScholarListItem")
    public record Item(
        long id, String name, String email, String maskedPhone, String cohortName,
        String university, String status, String accountStatus
    ) {
    }
}
