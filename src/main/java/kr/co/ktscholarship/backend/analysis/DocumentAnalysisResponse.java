package kr.co.ktscholarship.backend.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 문서 분석 결과 한 건.
 *
 * @param outdated 분석 뒤 장학생이 입력값을 바꿔서 이 결과가 지금 입력과 맞지 않음 (다시 분석 필요)
 */
@Schema(name = "DocumentAnalysisResponse")
public record DocumentAnalysisResponse(
    long id,
    long submissionId,
    long fileId,
    int attemptNumber,
    @Schema(allowableValues = {"COMPLETED", "FAILED"}) String status,
    @Schema(allowableValues = {"MATCH", "MISMATCH", "UNREADABLE"}) AnswerComparison.Result overallResult,
    List<AnswerComparison.Item> comparisons,
    @Schema(description = "실패 이유 코드 (OCR_NOT_CONFIGURED, OCR_REQUEST_FAILED, STORAGE_UNAVAILABLE 등). 성공이면 null")
    String failureReason,
    boolean outdated,
    Instant requestedAt,
    Instant completedAt
) {
}
