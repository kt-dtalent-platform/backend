package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 담당자 개별 검토 화면. version은 확인 완료·보완 요청 때 함께 보내 동시 수정을 막는 데 쓴다.
 */
@Schema(name = "SubmissionDetailResponse")
public record SubmissionDetailResponse(
    long id,
    long requestId,
    String requestTitle,
    Scholar scholar,
    @Schema(allowableValues = {"SUBMITTED", "REVISION_REQUIRED", "RESUBMITTED", "CONFIRMED"}) String status,
    Instant submittedAt,
    boolean late,
    List<Field> fields,
    Map<String, Object> answers,
    @Schema(description = "업로드가 끝난 파일만. 원본은 원본 보기 주소 API로 연다") List<File> files,
    @Schema(description = "최신 문서 분석 결과. 분석 전이거나 분석 뒤 입력값이 바뀌었으면 null")
    kr.co.ktscholarship.backend.analysis.AnswerComparison analysis,
    @Schema(description = "상태가 바뀐 순서대로") List<History> history,
    @Schema(description = "보완 요청, 최근 것이 먼저") List<Revision> revisions,
    int version
) {

    @Schema(name = "SubmissionDetailScholar")
    public record Scholar(long id, String name, String cohortName) {
    }

    @Schema(name = "SubmissionDetailField")
    public record Field(String fieldKey, String label, String fieldType) {
    }

    @Schema(name = "SubmissionDetailFile")
    public record File(long id, String fieldKey, String originalName, String contentType, long sizeBytes) {
    }

    @Schema(name = "SubmissionDetailHistory")
    public record History(String from, String to, String actorType, Instant changedAt) {
    }

    @Schema(name = "SubmissionDetailRevision")
    public record Revision(
        List<String> fieldKeys,
        String reason,
        @Schema(description = "보완 기한. 지금은 따로 두지 않아 null") Instant dueAt,
        Instant resolvedAt
    ) {
    }
}
