package kr.co.ktscholarship.backend.analysis;

import java.math.BigDecimal;

/**
 * 성적증명서에서 찾은 값. null = 확인 불가(못 찾았거나 확신이 없음).
 *
 * @param readable       문서가 온전한가(총계·끝 문구가 있음). false면 모든 값이 null
 * @param semesterFound  대상 학기 제목이 문서에 있는가. false면 학기 값은 "이 학기 문서가 아님"
 */
public record ExtractedTranscript(
    boolean readable,
    boolean semesterFound,
    BigDecimal semesterGpa,
    BigDecimal semesterCredits,
    BigDecimal gpaScale,
    BigDecimal cumulativeGpa,
    BigDecimal cumulativeCredits
) {

    static ExtractedTranscript unreadable() {
        return new ExtractedTranscript(false, false, null, null, null, null, null);
    }
}
