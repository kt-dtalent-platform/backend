package kr.co.ktscholarship.backend.analysis;

import java.util.List;

/**
 * 입력값과 문서 값 비교 결과. 프론트 SubmissionDetail.analysis와 같은 모양.
 *
 * @param overallResult 하나라도 다르면 MISMATCH, 다른 건 없고 못 읽은 게 있으면 UNREADABLE, 모두 같으면 MATCH
 */
public record AnswerComparison(Result overallResult, List<Item> comparisons) {

    public enum Result { MATCH, MISMATCH, UNREADABLE }

    /** entered·extracted는 화면에 그대로 보여 줄 글자. 못 읽었으면 extracted는 null */
    public record Item(String field, String label, String entered, String extracted, Result result) {
    }
}
