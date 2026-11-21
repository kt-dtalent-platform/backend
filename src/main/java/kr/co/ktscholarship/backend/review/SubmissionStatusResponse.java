package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import kr.co.ktscholarship.backend.request.RequestStatus;

/**
 * 요청별 제출 현황. summary는 상태·검색 조건과 상관없이 대상자 전체 기준이고,
 * items·total은 조건을 적용한 결과다.
 */
@Schema(name = "SubmissionStatusResponse")
public record SubmissionStatusResponse(
    RequestInfo request,
    Summary summary,
    List<Item> items,
    int page,
    int pageSize,
    long total
) {

    @Schema(name = "SubmissionStatusRequest")
    public record RequestInfo(
        long id,
        String title,
        @Schema(description = "프로그램 전체 대상이면 null") String cohortName,
        Instant closesAt,
        @Schema(description = "요청 상태. 마감(CLOSED)이면 화면에서 보완 요청 등을 막는 데 쓴다") RequestStatus status
    ) {
    }

    /** submitted는 검토 대기(제출 + 재제출) 수. notSubmitted에는 작성 중(DRAFT)도 들어간다 */
    @Schema(name = "SubmissionStatusSummary")
    public record Summary(long target, long notSubmitted, long submitted, long revisionRequired, long confirmed) {
    }

    @Schema(name = "SubmissionStatusItem")
    public record Item(
        long scholarId,
        String scholarName,
        String cohortName,
        String university,
        @Schema(description = "답변의 gpa. 없거나 숫자가 아니면 null") Double gpa,
        @Schema(description = "답변의 gpa_scale. 없으면 null") String gpaScale,
        @Schema(description = "제출 전(작성 중 포함)이면 null") Long submissionId,
        @Schema(allowableValues = {"NOT_SUBMITTED", "SUBMITTED", "REVISION_REQUIRED", "RESUBMITTED", "CONFIRMED"})
        String status,
        Instant submittedAt,
        boolean late,
        @Schema(description = "문서 분석 연결 전까지 항상 false") boolean hasInputError
    ) {
    }
}
