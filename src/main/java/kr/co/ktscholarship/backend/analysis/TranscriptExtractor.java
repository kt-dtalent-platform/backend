package kr.co.ktscholarship.backend.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * OCR 단어 좌표에서 성적증명서 값을 찾는다.
 *
 * <p>원칙: 확실하지 않으면 값을 내지 않는다(null = 확인 불가). 틀린 값을 내는 것이 가장 나쁘다.
 * 규칙은 합성 130장·실물 11장 실험(2026-11-17)과 실물 2장(3단 배치, 2026-11-20)에서 정리했다.
 *
 * <ol>
 *   <li>단어를 y 좌표로 묶어 줄을 만들고, 학기 소계 라벨이 시작하는 x 위치로 칸을 나눈다 (2단·3단 성적표).
 *       라벨이 한 칸에서만 보이면 페이지 가운데로 나눈다</li>
 *   <li>대상 학기 제목과 같은 칸, 그 아래, 다음 제목 전의 첫 소계가 학기 소계.
 *       소계는 "취득학점 … 평점평균 …" 한 줄이거나, "학점계 / 평점계 / 평점평균"처럼 세로로 쌓인 줄</li>
 *   <li>누적: "총취득학점 … 평점평균 X / 4.50", 위쪽 표의 "총평점평균" 열, 만점이 붙은 "평점평균 X / 4.5",
 *       또는 아래쪽 표의 "계 · 총평점 · 평균평점" 열 아래 값</li>
 *   <li>라벨은 OCR이 자주 틀려서(평점평균 → 행정평균) 비슷한 글자도 같은 라벨로 본다</li>
 *   <li>숫자 확신도가 0.9 미만이면 확인 불가. "증명" 문구나 총계가 없으면 잘린 문서로 보고 전부 확인 불가</li>
 * </ol>
 */
@Component
public class TranscriptExtractor {

    static final double MIN_CONFIDENCE = 0.9;

    private static final Pattern NUMBER = Pattern.compile("^[()]*(\\d+[.,]\\d+|\\d+)[()]*$");
    // "3.06/4.5"처럼 붙어 읽힌 경우, "3.94/"처럼 만점이 다음 단어로 떨어진 경우
    private static final Pattern SLASHED = Pattern.compile("^(\\d+[.,]\\d+)/(4\\.[35]0?)?$");
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})(학년[도호]?|년도)$");
    private static final Pattern REGULAR_TERM = Pattern.compile("^([12])학기$");

    public ExtractedTranscript extract(OcrPage page, TargetSemester target) {
        List<List<OcrPage.Word>> lines = groupLines(page.words());
        Columns columns = Columns.of(lines, page.width());

        List<Header> headers = findHeaders(lines, columns);
        List<Subtotal> subtotals = findSubtotals(lines, columns);
        Total total = findTotal(lines, columns);

        boolean complete = page.words().stream().anyMatch(w -> w.text().contains("증명"));
        if (!total.found || !complete) {
            return ExtractedTranscript.unreadable();
        }

        BigDecimal semesterGpa = null;
        BigDecimal semesterCredits = null;
        Header header = headers.stream().filter(h -> h.matches(target)).findFirst().orElse(null);
        if (header != null) {
            double nextHeaderY = headers.stream()
                .filter(h -> h.column == header.column && h.y > header.y)
                .mapToDouble(h -> h.y).min().orElse(Double.MAX_VALUE);
            Subtotal subtotal = subtotals.stream()
                .filter(s -> s.column == header.column && s.y > header.y && s.y < nextHeaderY)
                .min(Comparator.comparingDouble(s -> s.y))
                .orElse(null);
            if (subtotal != null) {
                semesterGpa = accept(subtotal.gpa);
                semesterCredits = accept(subtotal.credits);
            }
        }
        return new ExtractedTranscript(true, header != null, semesterGpa, semesterCredits,
            accept(total.scale), accept(total.gpa), accept(total.credits));
    }

    // ---------- 줄 만들기 ----------

    static List<List<OcrPage.Word>> groupLines(List<OcrPage.Word> words) {
        List<OcrPage.Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(OcrPage.Word::centerY).thenComparingInt(OcrPage.Word::x0));
        List<List<OcrPage.Word>> lines = new ArrayList<>();
        for (OcrPage.Word word : sorted) {
            if (!lines.isEmpty()) {
                List<OcrPage.Word> last = lines.get(lines.size() - 1);
                double tolerance = Math.max(6, word.height() * 0.5);
                if (Math.abs(last.get(last.size() - 1).centerY() - word.centerY()) < tolerance) {
                    last.add(word);
                    continue;
                }
            }
            lines.add(new ArrayList<>(List.of(word)));
        }
        lines.forEach(line -> line.sort(Comparator.comparingInt(OcrPage.Word::x0)));
        return lines;
    }

    // ---------- 칸 나누기 ----------

    /**
     * 학기 소계 라벨("취득학점", "학점계")은 칸마다 왼쪽 끝에 맞춰 찍혀서, 라벨의 x 위치 묶음이 곧 칸의 시작이다.
     * 학기 제목은 칸 가운데에 찍히는 학교가 있어 기준으로 쓰지 않는다.
     * 마지막 칸 오른쪽 너머(소계 라벨이 없는 칸, 예: 이수 구분 안내 칸)는 별도 칸으로 둔다
     */
    private record Columns(double[] bounds, double rightLimit) {

        static Columns of(List<List<OcrPage.Word>> lines, int width) {
            List<Integer> starts = new ArrayList<>();
            List<Integer> all = new ArrayList<>();
            for (int li = 0; li < lines.size(); li++) {
                for (OcrPage.Word word : lines.get(li)) {
                    if (!isSubtotalCreditLabel(word.text())) {
                        continue;
                    }
                    all.add(word.x0());
                    // 누적 구역("취득학점 148 / 평점평균 4.5/4.5")은 칸 왼쪽 끝이 아닌 곳에 찍히기도 해서 칸 기준에서 뺀다.
                    // 학기 소계가 모두 이 모양이면(누적과 구분 불가) 전부 쓴다
                    if (!hasStackedScaledGpa(lines, li, word)) {
                        starts.add(word.x0());
                    }
                }
            }
            if (starts.isEmpty()) {
                starts = all;
            }
            starts.sort(Comparator.naturalOrder());
            List<Integer> clusters = new ArrayList<>();
            for (int i = 0; i < starts.size(); i++) {
                if (i == 0 || starts.get(i) - starts.get(i - 1) > width * 0.1) {
                    clusters.add(starts.get(i));
                }
            }
            if (clusters.size() < 2) {
                // 라벨이 한 칸에서만 보이면 예전처럼 페이지 가운데로 나눈다 (2단 성적표)
                return new Columns(new double[] {width / 2.0}, Double.MAX_VALUE);
            }
            double[] bounds = new double[clusters.size() - 1];
            for (int i = 1; i < clusters.size(); i++) {
                int gap = clusters.get(i) - clusters.get(i - 1);
                // 제목이 라벨보다 조금 왼쪽에서 시작해도 같은 칸에 들도록 여유를 둔다
                bounds[i - 1] = clusters.get(i) - gap * 0.1;
            }
            double width2 = (clusters.getLast() - clusters.getFirst()) / (double) (clusters.size() - 1);
            return new Columns(bounds, clusters.getLast() + width2);
        }

        int of(OcrPage.Word word) {
            if (word.x0() >= rightLimit) {
                return bounds.length + 1;
            }
            int column = 0;
            while (column < bounds.length && word.x0() >= bounds[column]) {
                column++;
            }
            return column;
        }
    }

    // ---------- 학기 제목 "2026학년도 1학기", "(2022학년도 1 학기)", "[ 2024학년도 여름계절학기 ]" ----------

    private List<Header> findHeaders(List<List<OcrPage.Word>> lines, Columns columns) {
        List<Header> headers = new ArrayList<>();
        for (List<OcrPage.Word> line : lines) {
            for (int i = 0; i + 1 < line.size(); i++) {
                OcrPage.Word word = line.get(i);
                Matcher year = YEAR.matcher(unbracket(word.text()));
                OcrPage.Word next = line.get(i + 1);
                if (!year.matches() || next.x0() - word.x1() > 60) {
                    continue;
                }
                String nextText = unbracket(next.text());
                // "1 학기"처럼 학기 번호와 "학기"가 따로 읽힌 경우
                if (nextText.matches("[12]") && i + 2 < line.size()
                        && unbracket(line.get(i + 2).text()).equals("학기")) {
                    nextText = nextText + "학기";
                }
                String term = termOf(nextText);
                if (term != null) {
                    headers.add(new Header(Integer.parseInt(year.group(1)), term,
                        columns.of(word), word.centerY()));
                }
            }
        }
        return headers;
    }

    /** 제목을 감싼 괄호 "(2022학년도", "학기)", "[" 를 뗀다 */
    private static String unbracket(String text) {
        return text.replaceAll("^[(\\[]+|[)\\]]+$", "");
    }

    /**
     * 학기 이름. 모르는 학기(교류계절학기 등)도 "OTHER"로 제목으로 남겨서 앞 학기의 소계 찾기가 거기서 멈추게 한다.
     * 이 학기의 소계를 앞 학기 소계로 잘못 가져오지 않게
     */
    private static String termOf(String text) {
        Matcher regular = REGULAR_TERM.matcher(text);
        if (regular.matches()) {
            return regular.group(1);
        }
        if (text.contains("계절")) {
            if (text.contains("여름") || text.contains("하계")) {
                return "SUMMER";
            }
            if (text.contains("겨울") || text.contains("동계")) {
                return "WINTER";
            }
        }
        return text.endsWith("학기") ? "OTHER" : null;
    }

    // ---------- 학기 소계 "취득학점: 14.0 평점평균: 3.75" 또는 세로 "학점계 : 18.0 / 평점계 / 평점평균 : 4.17" ----------

    private static boolean isSubtotalCreditLabel(String text) {
        return (isLabel(text, "취득학점") && !isTotalCreditLabel(text)) || isExactLabel(text, "학점계");
    }

    /** "총취득학점", "인정학점총계". 콜론 앞 라벨만 본다 ("취득학점:14.0"의 숫자가 오타 허용에 섞여 총계로 보이지 않게) */
    private static boolean isTotalCreditLabel(String text) {
        String label = labelOnly(text);
        return isLabel(label, "총취득학점") || isLabel(label, "인정학점총계");
    }

    private static String labelOnly(String text) {
        int colon = text.indexOf(':');
        return colon < 0 ? text : text.substring(0, colon);
    }

    /** 학점 라벨 바로 아래(같은 x) 두 줄 안에 만점이 붙은 "평점평균 X / 4.5"가 있는가 (세로로 쌓인 누적 구역 모양) */
    private static boolean hasStackedScaledGpa(List<List<OcrPage.Word>> lines, int lineIndex, OcrPage.Word creditLabel) {
        for (int below = lineIndex + 1; below < Math.min(lines.size(), lineIndex + 3); below++) {
            List<OcrPage.Word> line = lines.get(below);
            for (int j = 0; j < line.size(); j++) {
                OcrPage.Word w = line.get(j);
                if (Math.abs(w.x0() - creditLabel.x0()) <= Math.max(20, creditLabel.height())
                        && isLabel(w.text(), "평점평균") && scaleAfter(line, j) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Subtotal> findSubtotals(List<List<OcrPage.Word>> lines, Columns columns) {
        List<Subtotal> subtotals = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            List<OcrPage.Word> line = lines.get(li);
            for (int i = 0; i < line.size(); i++) {
                OcrPage.Word word = line.get(i);
                if (!isSubtotalCreditLabel(word.text())) {
                    continue;
                }
                int column = columns.of(word);
                Found credits = numberAfter(line, i);
                Found gpa = null;
                for (int j = i + 1; j < line.size(); j++) {
                    if (isLabel(line.get(j).text(), "평점평균") && columns.of(line.get(j)) == column) {
                        gpa = numberAfter(line, j);
                        break;
                    }
                }
                if (gpa == null) {
                    gpa = stackedGpa(lines, li, word);
                }
                subtotals.add(new Subtotal(column, word.centerY(), credits, gpa));
            }
        }
        return subtotals;
    }

    /** 학점 라벨 바로 아래 몇 줄 안에서 같은 x에 시작하는 "평점평균"의 값 (세로로 쌓인 소계) */
    private static Found stackedGpa(List<List<OcrPage.Word>> lines, int lineIndex, OcrPage.Word creditLabel) {
        for (int below = lineIndex + 1; below < Math.min(lines.size(), lineIndex + 4); below++) {
            List<OcrPage.Word> line = lines.get(below);
            for (int j = 0; j < line.size(); j++) {
                OcrPage.Word w = line.get(j);
                if (Math.abs(w.x0() - creditLabel.x0()) > Math.max(20, creditLabel.height())) {
                    continue;
                }
                if (isSubtotalCreditLabel(w.text()) || isTotalCreditLabel(w.text()) || isLabel(w.text(), "총평점평균")) {
                    return null; // 평점평균 전에 다음 소계나 총계가 시작됨 (총계의 평점을 학기 평점으로 가져오지 않게)
                }
                if (isLabel(w.text(), "평점평균")) {
                    return numberAfter(line, j);
                }
            }
        }
        return null;
    }

    // ---------- 총계(누적) ----------

    private Total findTotal(List<List<OcrPage.Word>> lines, Columns columns) {
        Total total = new Total();
        for (List<OcrPage.Word> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                String text = line.get(i).text();
                if (isTotalCreditLabel(text)) {
                    total.found = true;
                    Found credits = numberAfter(line, i);
                    if (credits != null) {
                        total.credits = credits;
                        total.creditsFromStack = false;
                    }
                }
                if (isExactLabel(text, "평균평점") && line.stream().anyMatch(w -> isExactLabel(w.text(), "총평점"))) {
                    // 아래쪽 표 "계 | 총평점 | 평균평점" 머리줄 → 각 열 아래 값 (덕성여대형)
                    total.found = true;
                    readSummaryTable(lines, line, line.get(i), total);
                    continue;
                }
                boolean cumulativeLabel = isLabel(text, "총평점평균");
                // C형처럼 줄(칸) 맨 앞의 "평점평균 X / 4.5"도 누적으로 본다.
                // 만점이 붙어야 한다: 세로로 쌓인 학기 소계("평점평균 : 4.17")도 줄 맨 앞에 오기 때문
                boolean leading = i == 0 || columns.of(line.get(i - 1)) != columns.of(line.get(i));
                boolean leadingGpa = leading && isLabel(text, "평점평균") && scaleAfter(line, i) != null;
                if (!cumulativeLabel && !leadingGpa && !(isLabel(text, "평점평균") && lineHasTotal(line))) {
                    continue;
                }
                total.found = true;
                Found gpa = numberAfter(line, i);
                if (gpa != null) {
                    total.gpa = gpa;
                } else if (cumulativeLabel) {
                    // B형: 총평점평균 값이 줄에 없으면 위쪽 표에서 같은 열 아래 값
                    Found fromTable = valueBelow(lines, "총평점평균");
                    if (fromTable != null) {
                        total.gpa = fromTable;
                    }
                }
                Found scale = scaleAfter(line, i);
                if (scale != null) {
                    total.scale = scale;
                }
                if (leadingGpa) {
                    // "취득학점 : 148 / 평점평균 : 4.5/4.5"처럼 바로 위에 쌓인 학점 (성신여대형).
                    // 더 아래 후보가 누적이면 평점과 학점을 같은 구역 값으로 함께 바꾼다
                    // (앞선 학기 소계에도 만점이 있으면 그 학기 학점이 누적 학점으로 남던 문제)
                    Found above = creditsAbove(lines, line, line.get(i));
                    if (above != null) {
                        total.credits = above;
                        total.creditsFromStack = true;
                    } else if (total.creditsFromStack) {
                        total.credits = null;
                        total.creditsFromStack = false;
                    }
                }
            }
        }
        return total;
    }

    private static Found creditsAbove(List<List<OcrPage.Word>> lines, List<OcrPage.Word> gpaLine, OcrPage.Word gpaLabel) {
        int index = lines.indexOf(gpaLine);
        for (int above = index - 1; above >= Math.max(0, index - 2); above--) {
            List<OcrPage.Word> line = lines.get(above);
            for (int j = 0; j < line.size(); j++) {
                OcrPage.Word w = line.get(j);
                // 가장 가까운 학점 라벨만 본다. 바로 위가 "총취득학점"이면 그 값이 누적이고, 그 위 학기 소계로 넘어가지 않는다
                if (Math.abs(w.x0() - gpaLabel.x0()) <= Math.max(20, gpaLabel.height())
                        && (isLabel(w.text(), "취득학점") || isTotalCreditLabel(w.text()))) {
                    return numberAfter(line, j);
                }
            }
        }
        return null;
    }

    /** "계 | 총평점 | 평균평점" 머리줄 아래 줄의 "132.0 | 486.0 | 4.23/4.5" */
    private void readSummaryTable(List<List<OcrPage.Word>> lines, List<OcrPage.Word> headerLine,
                                  OcrPage.Word gpaHeader, Total total) {
        OcrPage.Word below = wordBelow(lines, headerLine, gpaHeader);
        if (below != null) {
            Matcher slashed = SLASHED.matcher(below.text());
            if (slashed.matches() && slashed.group(2) != null) {
                total.gpa = new Found(decimal(slashed.group(1)), below.confidence());
                total.scale = new Found(decimal(slashed.group(2)), below.confidence());
            }
        }
        headerLine.stream().filter(w -> w.text().equals("계")).findFirst().ifPresent(sum -> {
            OcrPage.Word credits = wordBelow(lines, headerLine, sum);
            BigDecimal n = credits == null ? null : toNumber(credits.text());
            if (n != null) {
                total.credits = new Found(n, credits.confidence());
                total.creditsFromStack = false;
            }
        });
    }

    private static OcrPage.Word wordBelow(List<List<OcrPage.Word>> lines, List<OcrPage.Word> headerLine,
                                          OcrPage.Word header) {
        // 표 칸 사이에 다른 줄("교양 54.00 …")이 끼어 있어 줄 수가 아니라 거리로 본다
        int index = lines.indexOf(headerLine);
        for (int below = index + 1; below < lines.size(); below++) {
            for (OcrPage.Word w : lines.get(below)) {
                if (w.centerY() - header.centerY() > header.height() * 4) {
                    return null;
                }
                if (w.x0() < header.x1() && w.x1() > header.x0()) {
                    return w;
                }
            }
        }
        return null;
    }

    private static boolean lineHasTotal(List<OcrPage.Word> line) {
        return line.stream().anyMatch(w -> isLabel(w.text(), "총취득학점"));
    }

    private Found valueBelow(List<List<OcrPage.Word>> lines, String label) {
        for (int li = 0; li < lines.size(); li++) {
            for (OcrPage.Word header : lines.get(li)) {
                if (!isLabel(header.text(), label)) {
                    continue;
                }
                for (int below = li + 1; below < Math.min(lines.size(), li + 3); below++) {
                    for (OcrPage.Word w : lines.get(below)) {
                        if (w.x0() < header.x1() && w.x1() > header.x0() - 30) {
                            BigDecimal n = toNumber(w.text());
                            if (n != null && n.compareTo(new BigDecimal("4.5")) <= 0) {
                                return new Found(n, w.confidence());
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    // ---------- 숫자 읽기 ----------

    /**
     * 라벨 바로 오른쪽(같은 줄, 가까운 거리)의 숫자. "/"를 만나면 그 뒤는 만점이라 멈춘다.
     * 다른 라벨(한글 글자)을 만나도 멈춘다. 이 칸 숫자를 못 읽었을 때 옆 칸 숫자(예: 백분율)를 가져오지 않게
     */
    private static Found numberAfter(List<OcrPage.Word> line, int labelIndex) {
        OcrPage.Word label = line.get(labelIndex);
        // "취득학점:14.0"처럼 라벨과 숫자가 붙어 읽힌 경우
        Found attached = attachedNumber(label.text(), label.confidence());
        if (attached != null) {
            return attached;
        }
        for (int i = labelIndex + 1; i < line.size(); i++) {
            OcrPage.Word w = line.get(i);
            if (w.x0() - label.x1() > 200 || w.text().equals("/") || hasHangul(w.text())) {
                return null;
            }
            Matcher slashed = SLASHED.matcher(w.text());
            if (slashed.matches()) {
                return new Found(decimal(slashed.group(1)), w.confidence());
            }
            BigDecimal n = toNumber(w.text());
            if (n != null) {
                return new Found(n, w.confidence());
            }
            if (w.text().equals(":")) {
                continue;
            }
        }
        return null;
    }

    private static boolean hasHangul(String text) {
        return text.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }

    private static Found attachedNumber(String text, double confidence) {
        int colon = text.indexOf(':');
        if (colon < 0 || colon == text.length() - 1) {
            return null;
        }
        BigDecimal n = toNumber(text.substring(colon + 1));
        return n == null ? null : new Found(n, confidence);
    }

    /** "평점평균 3.81 / 4.50"의 4.50, 붙어 읽힌 "3.06/4.5"의 4.5 */
    private static Found scaleAfter(List<OcrPage.Word> line, int labelIndex) {
        for (int j = labelIndex + 1; j < line.size(); j++) {
            OcrPage.Word w = line.get(j);
            Matcher slashed = SLASHED.matcher(w.text());
            if (slashed.matches()) {
                if (slashed.group(2) != null) {
                    return new Found(decimal(slashed.group(2)), w.confidence());
                }
                return j + 1 < line.size() ? scale(line.get(j + 1)) : null;
            }
            if (w.text().equals("/")) {
                return j + 1 < line.size() ? scale(line.get(j + 1)) : null;
            }
        }
        return null;
    }

    private static Found scale(OcrPage.Word word) {
        BigDecimal n = toNumber(word.text());
        if (n == null) {
            return null;
        }
        boolean known = n.compareTo(new BigDecimal("4.3")) == 0 || n.compareTo(new BigDecimal("4.5")) == 0;
        return known ? new Found(n, word.confidence()) : null;
    }

    /** 평점 자리의 "3,744"는 OCR이 소수점을 쉼표로 읽은 것으로 본다 */
    static BigDecimal toNumber(String text) {
        Matcher m = NUMBER.matcher(text);
        return m.matches() ? decimal(m.group(1)) : null;
    }

    private static BigDecimal decimal(String text) {
        return new BigDecimal(text.replace(',', '.'));
    }

    private static BigDecimal accept(Found found) {
        return found == null || found.confidence < MIN_CONFIDENCE ? null : found.value;
    }

    // ---------- 라벨 비교 ----------

    /**
     * OCR이 라벨을 자주 틀리게 읽어서(평점평균 → 행정평균·평설평균, 총취득학점 → 충취북학점)
     * 앞부분이 비슷하면 같은 라벨로 본다. 4글자는 1글자, 5글자 이상은 2글자까지 달라도 된다.
     */
    static boolean isLabel(String text, String label) {
        String cleaned = text.replace(":", "").replace(" ", "");
        if (cleaned.length() < label.length()) {
            return false;
        }
        if (cleaned.startsWith(label)) {
            return true;
        }
        // "총신청학점"은 "총취득학점"과 두 글자만 달라 오타 허용에 걸리지만 뜻이 다른 라벨이다
        if (label.contains("취득") && cleaned.contains("신청")) {
            return false;
        }
        // "행정평균"처럼 앞 두 글자가 모두 틀려도 "□□평균" 모양이면 평점평균으로 본다 ("평점환산"은 아님)
        if (label.equals("평점평균") && cleaned.substring(0, 4).endsWith("평균")) {
            return true;
        }
        int allowed = label.length() >= 5 ? 2 : label.length() == 4 ? 1 : 0;
        return distance(cleaned.substring(0, label.length()), label) <= allowed;
    }

    /** 짧은 라벨("학점계", "평균평점", "총평점")은 오타 허용 없이 그대로 읽힌 경우만 본다 */
    private static boolean isExactLabel(String text, String label) {
        return text.replace(":", "").replace(" ", "").equals(label);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    // ---------- 내부 값 ----------

    private record Found(BigDecimal value, double confidence) {
    }

    private record Header(int year, String term, int column, double y) {
        boolean matches(TargetSemester target) {
            return year == target.year() && term.equals(target.semester());
        }
    }

    private record Subtotal(int column, double y, Found credits, Found gpa) {
    }

    private static final class Total {
        boolean found;
        Found credits;
        Found gpa;
        Found scale;
        // credits가 세로로 쌓인 누적 구역에서 온 값인가 (다음 누적 후보가 오면 함께 바꾼다)
        boolean creditsFromStack;
    }
}
