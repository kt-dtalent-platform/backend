package kr.co.ktscholarship.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 합성 성적증명서 40장(형식 10종 × 원본·폰 촬영·심한 흐림·아래 잘림)으로 값 추출을 채점한다.
 * 가장 중요한 기준: 틀린 값을 내는 경우 0건. 못 찾으면 확인 불가(null)로 두는 것은 괜찮다.
 */
class TranscriptExtractorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TargetSemester TARGET = new TargetSemester(2026, "1");
    private static final TranscriptExtractor EXTRACTOR = new TranscriptExtractor();

    private static List<Map<String, String>> answers;

    @BeforeAll
    static void loadAnswers() throws IOException {
        List<String> lines = read("answers.csv").lines().toList();
        String[] header = lines.get(0).split(",");
        answers = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], cells[i]);
            }
            answers.add(row);
        }
    }

    @Test
    void neverReturnsWrongValueAcrossSyntheticSamples() throws IOException {
        Map<String, Function<ExtractedTranscript, BigDecimal>> fields = new LinkedHashMap<>();
        fields.put("semester_gpa", ExtractedTranscript::semesterGpa);
        fields.put("semester_credits", ExtractedTranscript::semesterCredits);
        fields.put("gpa_scale", ExtractedTranscript::gpaScale);
        fields.put("cumulative_gpa", ExtractedTranscript::cumulativeGpa);

        List<String> wrong = new ArrayList<>();
        int correct = 0;
        int total = 0;
        for (Map<String, String> row : answers) {
            ExtractedTranscript got = extract(row.get("file"));
            for (var field : fields.entrySet()) {
                BigDecimal expected = expected(row, field.getKey());
                BigDecimal value = field.getValue().apply(got);
                total++;
                if (expected == null) {
                    if (value == null) {
                        correct++;
                    } else {
                        wrong.add(row.get("file") + " " + field.getKey() + " 기대=확인 불가, 결과=" + value);
                    }
                } else if (value != null) {
                    if (value.compareTo(expected) == 0) {
                        correct++;
                    } else {
                        wrong.add(row.get("file") + " " + field.getKey() + " 기대=" + expected + ", 결과=" + value);
                    }
                }
            }
        }
        System.out.printf("합성 %d장 × 4항목: 맞음 %d/%d, 틀림 %d%n", answers.size(), correct, total, wrong.size());
        assertThat(wrong).as("틀린 값을 낸 경우").isEmpty();
        // 2026-11-18 기준 맞음 비율. 규칙이 나빠지면 여기서 걸린다
        assertThat(correct * 100 / total).as("맞음 비율(%%) %d/%d", correct, total).isGreaterThanOrEqualTo(85);
    }

    @Test
    void readsTwoColumnSemesterSubtotalWithoutMixingColumns() throws IOException {
        // S01: 2026-1 소계가 오른쪽 칸, 바로 아래 줄에 왼쪽 칸(2023-2, 평점 4.08) 소계가 섞여 나온다
        ExtractedTranscript got = extract("S01_v00.png");

        assertThat(got.readable()).isTrue();
        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterGpa()).isEqualByComparingTo("3.75");
        assertThat(got.semesterCredits()).isEqualByComparingTo("14.0");
        assertThat(got.gpaScale()).isEqualByComparingTo("4.5");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("3.81");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("91.0");
    }

    @Test
    void reportsMissingSemesterAndUnprintedSemesterGpa() throws IOException {
        // S03: 대상 학기(2026-1)가 문서에 없다
        ExtractedTranscript noSemester = extract("S03_v00.png");
        assertThat(noSemester.readable()).isTrue();
        assertThat(noSemester.semesterFound()).isFalse();
        assertThat(noSemester.semesterGpa()).isNull();

        // S04: 학기 평점이 인쇄되지 않는 형식. 누적은 위쪽 표에서 읽는다
        ExtractedTranscript notPrinted = extract("S04_v00.png");
        assertThat(notPrinted.semesterFound()).isTrue();
        assertThat(notPrinted.semesterGpa()).isNull();
        assertThat(notPrinted.cumulativeGpa()).isEqualByComparingTo("3.64");
    }

    @Test
    void treatsBlurredOrCutDocumentsAsUnreadable() throws IOException {
        for (String file : List.of("S01_v10.png", "S04_v10.png", "S08_v10.png")) {
            assertThat(extract(file).readable()).as(file).isFalse();
        }
    }

    @Test
    void handlesCommaDecimalAndSlashedNumbers() throws IOException {
        // S08: 4.3 만점, OCR이 "3,790"처럼 소수점을 쉼표로 읽는다
        ExtractedTranscript got = extract("S08_v00.png");
        assertThat(got.gpaScale()).isEqualByComparingTo("4.3");
        assertThat(got.semesterGpa()).isEqualByComparingTo("3.700");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("3.749");
    }

    @Test
    void matchesMisreadLabels() {
        // 실물 성적증명서에서 OCR이 실제로 틀리게 읽은 라벨
        assertThat(TranscriptExtractor.isLabel("행정평균:", "평점평균")).isTrue();
        assertThat(TranscriptExtractor.isLabel("평설평균:", "평점평균")).isTrue();
        assertThat(TranscriptExtractor.isLabel("충취북학점", "총취득학점")).isTrue();
        assertThat(TranscriptExtractor.isLabel("총취득하검", "총취득학점")).isTrue();
        // 다른 라벨과는 섞이지 않는다
        assertThat(TranscriptExtractor.isLabel("평점환산", "평점평균")).isFalse();
        assertThat(TranscriptExtractor.isLabel("총평점평균", "총취득학점")).isFalse();
        assertThat(TranscriptExtractor.isLabel("총취득학점", "취득학점")).isFalse();
    }

    // ---------- 도움 메서드 ----------

    private static BigDecimal expected(Map<String, String> row, String field) {
        if (!"True".equals(row.get("expected_readable"))) {
            return null;
        }
        if (field.startsWith("semester")) {
            if (!"True".equals(row.get("has_target_semester"))) {
                return null;
            }
            if (field.equals("semester_gpa") && !"True".equals(row.get("semester_gpa_printed"))) {
                return null;
            }
        }
        String value = row.get(field);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static ExtractedTranscript extract(String file) throws IOException {
        String name = file.substring(0, file.lastIndexOf('.')) + ".json";
        return EXTRACTOR.extract(UpstageOcrParser.parse(MAPPER, read(name)), TARGET);
    }

    private static String read(String name) throws IOException {
        try (InputStream in = TranscriptExtractorTest.class.getResourceAsStream("/ocr-samples/" + name)) {
            if (in == null) {
                throw new IOException("테스트 자료 없음: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
