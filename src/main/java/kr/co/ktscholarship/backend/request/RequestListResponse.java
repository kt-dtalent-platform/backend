package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record RequestListResponse(List<Item> items, int page, int pageSize, long total) {

    /**
     * cohortName: 프로그램 전체 대상이면 null.
     * publishedAt: 작성 중(DRAFT)이면 null.
     * submittedCount: 작성 중이 아닌 제출 수(보완 필요 포함). 미제출 = targetCount - submittedCount.
     * Swagger 스키마 이름이 다른 목록의 Item과 겹치지 않도록 이름을 따로 준다.
     */
    @Schema(name = "RequestListItem")
    public record Item(
        long id,
        String title,
        String templateCode,
        String cohortName,
        RequestStatus status,
        Instant publishedAt,
        Instant closesAt,
        long targetCount,
        long submittedCount,
        long revisionCount
    ) {
    }
}
