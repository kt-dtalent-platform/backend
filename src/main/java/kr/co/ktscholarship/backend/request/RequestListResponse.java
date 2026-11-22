package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record RequestListResponse(List<Item> items, int page, int pageSize, long total) {

    /**
     * cohortName: 프로그램 전체 대상이면 null.
     * publishedAt: 작성 중(DRAFT)이면 null.
     * submittedCount: 작성 중이 아닌 제출 수(보완 필요·확인 완료 포함). 미제출 = targetCount - submittedCount.
     * waitingCount: 검토 대기 수(제출 + 재제출). 대시보드의 waiting과 같은 기준.
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
        @Schema(description = "검토 대기 수(제출 + 재제출). 대시보드 waiting과 같은 기준") long waitingCount,
        long revisionCount
    ) {
    }
}
