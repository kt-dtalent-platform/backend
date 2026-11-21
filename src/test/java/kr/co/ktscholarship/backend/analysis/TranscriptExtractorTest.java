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
        // 두 글자만 다르지만 뜻이 다른 라벨
        assertThat(TranscriptExtractor.isLabel("총신청학점", "총취득학점")).isFalse();
        assertThat(TranscriptExtractor.isLabel("신청학점", "취득학점")).isFalse();
    }

    @Test
    void doesNotTakeNextFieldNumberWhenValueIsMissing() {
        // 학기 평점 숫자를 OCR이 놓친 소계 줄. 옆 칸 "백분율: 82.83"을 평점으로 읽으면 안 된다
        OcrPage page = new OcrPage(1000, 400, List.of(
            word("2026학년도", 50, 50), word("1학기", 150, 50),
            word("취득학점:", 50, 100), word("14.0", 150, 100), word("평점평균:", 250, 100),
            word("백분율:", 350, 100), word("82.83", 450, 100),
            word("총취득학점", 50, 300), word("91.0", 150, 300), word("평점평균", 250, 300),
            word("3.81", 350, 300), word("/", 450, 300), word("4.50", 550, 300),
            word("위와같이증명함", 50, 360)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.readable()).isTrue();
        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterCredits()).isEqualByComparingTo("14.0");
        assertThat(got.semesterGpa()).isNull();
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("3.81");
    }

    @Test
    void readsThreeColumnLayoutWithoutTakingLeftColumnSubtotal() {
        // 3단 성적표. 대상 학기 제목이 가운데 칸에 있는데 페이지 가운데(750)보다 왼쪽이라,
        // 예전처럼 반으로 나누면 왼쪽 칸의 다른 학기 소계(3학점·0.00)를 가져온다
        OcrPage page = new OcrPage(1500, 900, List.of(
            word("2026학년도", 600, 300), word("1학기", 700, 300),
            word("취득학점:", 50, 330), word("3", 150, 330), word("평점평균:", 250, 330), word("0.00", 350, 330),
            word("2024학년도", 150, 400), word("1학기", 250, 400),
            word("취득학점:", 50, 450), word("21", 150, 450), word("평점평균:", 250, 450), word("4.50", 350, 450),
            word("취득학점:", 550, 500), word("18", 650, 500), word("평점평균:", 750, 500), word("4.38", 850, 500),
            word("취득학점:", 1050, 600), word("15", 1150, 600),
            // 누적은 세로로 쌓이고, 같은 줄 왼쪽 칸에 다른 글자가 있다
            word("취득학점", 550, 700), word(":", 650, 700), word("148", 700, 700),
            word("A+", 400, 730), word("평점평균", 550, 730), word(":", 650, 730), word("4.5/4.5", 700, 730),
            word("증명합니다", 50, 800)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.readable()).isTrue();
        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterCredits()).isEqualByComparingTo("18");
        assertThat(got.semesterGpa()).isEqualByComparingTo("4.38");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("4.5");
        assertThat(got.gpaScale()).isEqualByComparingTo("4.5");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("148");
    }

    @Test
    void readsStackedSubtotalAndBottomSummaryTable() {
        // "(2026학년도 1 학기)" 제목, "학점계 / 평점계 / 평점평균" 세로 소계, 아래쪽 "계 | 총평점 | 평균평점" 표.
        // 줄 맨 앞의 학기 "평점평균 : 3.83"은 만점이 없으니 누적으로 보면 안 된다
        OcrPage page = new OcrPage(1000, 500, List.of(
            word("(2025학년도", 100, 50), word("1", 200, 50), word("학기)", 300, 50),
            word("(2026학년도", 600, 50), word("1", 700, 50), word("학기)", 800, 50),
            word("학점계", 50, 100), word(":", 150, 100), word("21.0", 200, 100),
            word("학점계", 550, 100), word(":", 650, 100), word("12.0", 700, 100),
            word("평점계", 50, 130), word("76.50", 200, 130), word("평점계", 550, 130), word("40.50", 700, 130),
            word("평점평균", 50, 160), word(":", 150, 160), word("3.83", 200, 160),
            word("평점평균", 550, 160), word(":", 650, 160), word("4.50", 700, 160),
            word("계", 500, 300), word("총평점", 600, 300), word("평균평점", 700, 300),
            word("54.00", 100, 330),
            word("132.0", 500, 360), word("486.0", 600, 360), word("4.23/4.5", 700, 360),
            word("증명합니다", 50, 420)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.readable()).isTrue();
        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterCredits()).isEqualByComparingTo("12.0");
        assertThat(got.semesterGpa()).isEqualByComparingTo("4.50");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("4.23");
        assertThat(got.gpaScale()).isEqualByComparingTo("4.5");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("132.0");
    }

    @Test
    void unknownTermHeaderStopsPreviousSemesterSubtotalSearch() {
        // 대상 학기 소계를 OCR이 놓쳤을 때, 그 아래 "교류계절학기"의 소계를 대상 학기 값으로 가져오면 안 된다
        OcrPage page = new OcrPage(1000, 400, List.of(
            word("2026학년도", 100, 50), word("1학기", 200, 50),
            word("2026학년도", 100, 120), word("교류계절학기", 200, 120),
            word("취득학점:", 50, 150), word("3", 150, 150), word("평점평균:", 250, 150), word("0.00", 350, 150),
            word("총취득학점", 50, 300), word("91.0", 150, 300), word("평점평균", 250, 300),
            word("3.81", 350, 300), word("/", 450, 300), word("4.50", 550, 300),
            word("증명합니다", 50, 360)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterCredits()).isNull();
        assertThat(got.semesterGpa()).isNull();
    }

    @Test
    void cumulativeBlockDoesNotShiftColumnBoundary() {
        // 2단, 학기 소계 라벨은 x=50·550. 아래쪽 누적 구역이 x=300에 찍혀 있다.
        // 누적 라벨까지 칸 기준에 넣으면 x=300 제목이 누적 구역과 같은 칸이 되어 학기 학점으로 148을 가져온다
        OcrPage page = new OcrPage(1000, 900, List.of(
            word("2026학년도", 300, 100), word("1학기", 400, 100),
            word("취득학점:", 50, 150), word("18", 150, 150), word("평점평균:", 250, 150), word("4.50", 350, 150),
            word("2025학년도", 800, 100), word("2학기", 900, 100),
            word("취득학점:", 550, 150), word("21", 650, 150), word("평점평균:", 750, 150), word("4.10", 850, 150),
            word("취득학점", 300, 700), word(":", 400, 700), word("148", 450, 700),
            word("평점평균", 300, 730), word(":", 400, 730), word("4.23/4.5", 450, 730),
            word("증명합니다", 50, 800)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.semesterCredits()).isEqualByComparingTo("18");
        assertThat(got.semesterGpa()).isEqualByComparingTo("4.50");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("148");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("4.23");
    }

    @Test
    void readsCreditsAttachedToLabelWithColon() {
        // "취득학점:14.0"이 한 단어로 읽혀도 총계("총취득학점")로 보지 않는다
        OcrPage page = new OcrPage(1000, 400, List.of(
            word("2026학년도", 50, 50), word("1학기", 150, 50),
            word("취득학점:14.0", 50, 100), word("평점평균:3.75", 250, 100),
            word("총취득학점", 50, 300), word("91.0", 150, 300), word("평점평균", 250, 300),
            word("3.81", 350, 300), word("/", 450, 300), word("4.50", 550, 300),
            word("증명합니다", 50, 360)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.semesterCredits()).isEqualByComparingTo("14.0");
        assertThat(got.semesterGpa()).isEqualByComparingTo("3.75");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("91.0");
    }

    @Test
    void stackedSearchStopsAtTotalSoCumulativeGpaIsNotTakenAsSemesterGpa() {
        // 학기 평점을 OCR이 놓친 세로 소계 바로 아래에 총계가 이어진다. 총계 평점 3.81을 학기 평점으로 가져오면 안 된다
        OcrPage page = new OcrPage(1000, 400, List.of(
            word("2026학년도", 50, 50), word("1학기", 150, 50),
            word("취득학점", 50, 100), word("14", 150, 100),
            word("총취득학점", 50, 130), word("91", 150, 130),
            word("평점평균", 50, 160), word("3.81/4.5", 150, 160),
            word("증명합니다", 50, 300)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.semesterCredits()).isEqualByComparingTo("14");
        assertThat(got.semesterGpa()).isNull();
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("3.81");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("91");
    }

    @Test
    void cumulativeGpaAndCreditsComeFromTheSameBlock() {
        // 학기 소계에도 만점이 붙어 있으면(4.00/4.5) 그 학점 18이 누적 학점으로 남으면 안 된다
        OcrPage page = new OcrPage(1000, 600, List.of(
            word("2026학년도", 50, 50), word("1학기", 150, 50),
            word("취득학점", 50, 100), word("18", 150, 100),
            word("평점평균", 50, 130), word("4.00/4.5", 150, 130),
            word("취득학점", 50, 400), word("148", 150, 400),
            word("평점평균", 50, 430), word("4.23/4.5", 150, 430),
            word("증명합니다", 50, 500)));

        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(got.semesterCredits()).isEqualByComparingTo("18");
        assertThat(got.semesterGpa()).isEqualByComparingTo("4.00");
        assertThat(got.cumulativeGpa()).isEqualByComparingTo("4.23");
        assertThat(got.cumulativeCredits()).isEqualByComparingTo("148");
    }

    @Test
    void findsSemesterOnSecondPage() {
        // 1페이지: 총계와 증명 문구, 2페이지: 대상 학기 (정윤 실물처럼 학기가 뒤 페이지에 있는 경우)
        String json = """
            {"pages":[
              {"width":1000,"height":1400,"words":[%s,%s,%s,%s,%s,%s]},
              {"width":1000,"height":1400,"words":[%s,%s,%s,%s,%s,%s]}
            ]}""".formatted(
            json("총취득학점", 50, 100), json("91.0", 150, 100), json("평점평균", 250, 100),
            json("3.81", 350, 100), json("/", 450, 100), json("4.50", 550, 100),
            json("2026학년도", 50, 50), json("1학기", 150, 50), json("취득학점:", 50, 100),
            json("14.0", 150, 100), json("평점평균:", 250, 100), json("증명함", 50, 300));

        OcrPage page = UpstageOcrParser.parse(MAPPER, json);
        ExtractedTranscript got = EXTRACTOR.extract(page, TARGET);

        assertThat(page.height()).isEqualTo(2800);
        assertThat(got.semesterFound()).isTrue();
        assertThat(got.semesterCredits()).isEqualByComparingTo("14.0");
    }

    private static OcrPage.Word word(String text, int x, int y) {
        return new OcrPage.Word(text, x, y, x + 90, y + 20, 0.99);
    }

    private static String json(String text, int x, int y) {
        return """
            {"text":"%s","confidence":0.99,"boundingBox":{"vertices":[{"x":%d,"y":%d},{"x":%d,"y":%d}]}}"""
            .formatted(text, x, y, x + 90, y + 20);
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
