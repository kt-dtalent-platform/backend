package kr.co.ktscholarship.backend.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.co.ktscholarship.backend.analysis.AnswerComparison.Item;
import kr.co.ktscholarship.backend.analysis.AnswerComparison.Result;
import org.springframework.stereotype.Component;

/**
 * 장학생 입력값과 성적증명서에서 읽은 값을 항목별로 비교한다.
 *
 * <p>비교 결과는 검토 보조용이다. 최종 판단은 담당자가 한다.
 * 못 읽은 값은 절대 "일치"가 되지 않는다(확인 불가).
 */
@Component
public class AnswerComparator {

    private static final List<Field> FIELDS = List.of(
        new Field("gpa", "학기 평점", ExtractedTranscript::semesterGpa, true),
        new Field("gpa_scale", "만점", ExtractedTranscript::gpaScale, false),
        new Field("credits", "취득 학점", ExtractedTranscript::semesterCredits, true)
    );

    public AnswerComparison compare(Map<String, Object> answers, ExtractedTranscript extracted) {
        List<Item> items = new ArrayList<>();
        for (Field field : FIELDS) {
            Object raw = answers.get(field.key);
            if (raw == null || raw.toString().isBlank()) {
                // 장학생이 입력하지 않은 항목은 비교하지 않는다
                continue;
            }
            String entered = raw.toString().strip();
            items.add(compareOne(field, entered, extracted));
        }
        return new AnswerComparison(overall(items), items);
    }

    private Item compareOne(Field field, String entered, ExtractedTranscript extracted) {
        if (!extracted.readable()) {
            return new Item(field.key, field.label, entered, null, Result.UNREADABLE);
        }
        // 문서는 온전한데 대상 학기가 없으면 다른 학기 증명서다
        if (field.semester && !extracted.semesterFound()) {
            return new Item(field.key, field.label, entered, null, Result.MISMATCH);
        }
        BigDecimal found = field.value.apply(extracted);
        if (found == null) {
            return new Item(field.key, field.label, entered, null, Result.UNREADABLE);
        }
        BigDecimal input = number(entered);
        // 입력값이 숫자가 아니면 문서 값과 같을 수 없다
        Result result = input != null && input.compareTo(found) == 0 ? Result.MATCH : Result.MISMATCH;
        return new Item(field.key, field.label, entered, found.toPlainString(), result);
    }

    private static Result overall(List<Item> items) {
        if (items.isEmpty()) {
            return Result.UNREADABLE;
        }
        if (items.stream().anyMatch(i -> i.result() == Result.MISMATCH)) {
            return Result.MISMATCH;
        }
        if (items.stream().anyMatch(i -> i.result() == Result.UNREADABLE)) {
            return Result.UNREADABLE;
        }
        return Result.MATCH;
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record Field(String key, String label, Function<ExtractedTranscript, BigDecimal> value,
                         boolean semester) {
    }
}
