package kr.co.ktscholarship.backend.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 입력값과 문서 값 비교 결과. 프론트 SubmissionDetail.analysis와 같은 모양.
 *
 * @param overallResult 하나라도 다르면 MISMATCH, 다른 건 없고 못 읽은 게 있으면 UNREADABLE, 모두 같으면 MATCH
 */
@Schema(name = "AnalysisComparison")
public record AnswerComparison(Result overallResult, List<Item> comparisons) {

    @Schema(name = "AnalysisResult")
    public enum Result { MATCH, MISMATCH, UNREADABLE }

    /** entered·extracted는 화면에 그대로 보여 줄 글자. 못 읽었으면 extracted는 null */
    // Swagger 스키마 이름이 다른 목록의 Item과 겹치지 않게 따로 준다
    @Schema(name = "AnalysisComparisonItem")
    public record Item(String field, String label, String entered, String extracted, Result result) {
    }
}
