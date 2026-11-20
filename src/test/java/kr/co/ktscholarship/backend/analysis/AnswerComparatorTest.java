package kr.co.ktscholarship.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.co.ktscholarship.backend.analysis.AnswerComparison.Result;
import org.junit.jupiter.api.Test;

class AnswerComparatorTest {

    private final AnswerComparator comparator = new AnswerComparator();

    // 2026-1 평점 3.75, 14학점, 4.5 만점이 읽힌 문서
    private static final ExtractedTranscript READ = new ExtractedTranscript(true, true,
        new BigDecimal("3.75"), new BigDecimal("14.0"), new BigDecimal("4.50"), new BigDecimal("3.81"), new BigDecimal("91.0"));

    @Test
    void matchesWhenEveryEnteredValueEqualsDocument() {
        AnswerComparison result = comparator.compare(answers("3.75", "4.5", "14"), READ);

        assertThat(result.overallResult()).isEqualTo(Result.MATCH);
        assertThat(result.comparisons())
            .extracting("field", "entered", "extracted", "result")
            .containsExactly(
                tuple("gpa", "3.75", "3.75", Result.MATCH),
                tuple("gpa_scale", "4.5", "4.50", Result.MATCH),      // 4.5와 4.50은 같은 값
                tuple("credits", "14", "14.0", Result.MATCH));
    }

    @Test
    void mismatchWinsOverUnreadable() {
        ExtractedTranscript gpaNotRead = new ExtractedTranscript(true, true,
            null, new BigDecimal("14.0"), new BigDecimal("4.5"), null, null);

        AnswerComparison result = comparator.compare(answers("3.75", "4.5", "15"), gpaNotRead);

        assertThat(result.overallResult()).isEqualTo(Result.MISMATCH);
        assertThat(result.comparisons()).extracting("result")
            .containsExactly(Result.UNREADABLE, Result.MATCH, Result.MISMATCH);
    }

    @Test
    void neverMatchesWhatWasNotRead() {
        ExtractedTranscript gpaNotRead = new ExtractedTranscript(true, true,
            null, new BigDecimal("14.0"), new BigDecimal("4.5"), null, null);

        AnswerComparison result = comparator.compare(answers("3.75", "4.5", "14"), gpaNotRead);

        assertThat(result.overallResult()).isEqualTo(Result.UNREADABLE);
        assertThat(result.comparisons().get(0).extracted()).isNull();
    }

    @Test
    void unreadableDocumentMakesEveryFieldUnreadable() {
        AnswerComparison result = comparator.compare(answers("3.75", "4.5", "14"), ExtractedTranscript.unreadable());

        assertThat(result.overallResult()).isEqualTo(Result.UNREADABLE);
        assertThat(result.comparisons()).extracting("result").containsOnly(Result.UNREADABLE);
    }

    @Test
    void documentWithoutTargetSemesterIsMismatch() {
        ExtractedTranscript otherSemester = new ExtractedTranscript(true, false,
            null, null, new BigDecimal("4.5"), new BigDecimal("4.20"), new BigDecimal("90"));

        AnswerComparison result = comparator.compare(answers("3.75", "4.5", "14"), otherSemester);

        assertThat(result.overallResult()).isEqualTo(Result.MISMATCH);
        assertThat(result.comparisons()).extracting("field", "result").containsExactly(
            tuple("gpa", Result.MISMATCH), tuple("gpa_scale", Result.MATCH), tuple("credits", Result.MISMATCH));
    }

    @Test
    void skipsBlankAnswersAndRejectsNonNumbers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("gpa", "삼점칠오");
        answers.put("gpa_scale", "");
        answers.put("memo", "비교 대상 아님");

        AnswerComparison result = comparator.compare(answers, READ);

        assertThat(result.comparisons()).extracting("field", "result").containsExactly(tuple("gpa", Result.MISMATCH));
        assertThat(comparator.compare(Map.of(), READ).overallResult()).isEqualTo(Result.UNREADABLE);
    }

    // 만점만 입력했어도 대상 학기가 없는 문서면 다른 학기 증명서라 불일치
    @Test
    void missingSemesterIsMismatchEvenWhenOnlyScaleEntered() {
        ExtractedTranscript otherSemester = new ExtractedTranscript(true, false,
            null, null, new BigDecimal("4.50"), new BigDecimal("3.81"), new BigDecimal("91.0"));
        Map<String, Object> onlyScale = new LinkedHashMap<>();
        onlyScale.put("gpa_scale", "4.5");

        AnswerComparison result = comparator.compare(onlyScale, otherSemester);

        assertThat(result.overallResult()).isEqualTo(Result.MISMATCH);
        assertThat(result.comparisons()).extracting("field", "result").containsExactly(tuple("gpa_scale", Result.MATCH));
    }

    private static Map<String, Object> answers(Object gpa, Object scale, Object credits) {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("gpa", gpa);
        answers.put("gpa_scale", scale);
        answers.put("credits", credits);
        return answers;
    }
}
