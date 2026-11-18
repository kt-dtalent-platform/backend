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
 * 규칙은 합성 130장·실물 11장 실험(2026-11-17)에서 정리했다.
 *
 * <ol>
 *   <li>단어를 y 좌표로 묶어 줄을 만들고, 페이지 가운데로 왼쪽·오른쪽 칸을 나눈다 (2단 성적표)</li>
 *   <li>대상 학기 제목과 같은 칸, 그 아래, 다음 제목 전의 첫 "취득학점 … 평점평균 …" 줄이 학기 소계</li>
 *   <li>누적: "총취득학점 … 평점평균 X / 4.50", 위쪽 표의 "총평점평균" 열, 또는 "평점평균 X / 4.5"</li>
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
        double middle = page.width() / 2.0;

        List<Header> headers = findHeaders(lines, middle);
        List<Subtotal> subtotals = findSubtotals(lines, middle);
        Total total = findTotal(lines);

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

    // ---------- 학기 제목 "2026학년도 1학기", "2024학년도 여름계절학기" ----------

    private List<Header> findHeaders(List<List<OcrPage.Word>> lines, double middle) {
        List<Header> headers = new ArrayList<>();
        for (List<OcrPage.Word> line : lines) {
            for (int i = 0; i + 1 < line.size(); i++) {
                OcrPage.Word word = line.get(i);
                Matcher year = YEAR.matcher(word.text());
                OcrPage.Word next = line.get(i + 1);
                if (!year.matches() || next.x0() - word.x1() > 60) {
                    continue;
                }
                String term = termOf(next.text());
                if (term != null) {
                    headers.add(new Header(Integer.parseInt(year.group(1)), term,
                        column(word, middle), word.centerY()));
                }
            }
        }
        return headers;
    }

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
        return null;
    }

    // ---------- 학기 소계 "취득학점: 14.0 평점평균: 3.75" ----------

    private List<Subtotal> findSubtotals(List<List<OcrPage.Word>> lines, double middle) {
        List<Subtotal> subtotals = new ArrayList<>();
        for (List<OcrPage.Word> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                OcrPage.Word word = line.get(i);
                if (!isLabel(word.text(), "취득학점") || isLabel(word.text(), "총취득학점")) {
                    continue;
                }
                int column = column(word, middle);
                Found credits = numberAfter(line, i);
                Found gpa = null;
                for (int j = i + 1; j < line.size(); j++) {
                    if (isLabel(line.get(j).text(), "평점평균") && column(line.get(j), middle) == column) {
                        gpa = numberAfter(line, j);
                        break;
                    }
                }
                subtotals.add(new Subtotal(column, word.centerY(), credits, gpa));
            }
        }
        return subtotals;
    }

    // ---------- 총계(누적) ----------

    private Total findTotal(List<List<OcrPage.Word>> lines) {
        Total total = new Total();
        for (List<OcrPage.Word> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                String text = line.get(i).text();
                if (isLabel(text, "총취득학점") || isLabel(text, "인정학점총계")) {
                    total.found = true;
                    Found credits = numberAfter(line, i);
                    if (credits != null) {
                        total.credits = credits;
                    }
                }
                boolean cumulativeLabel = isLabel(text, "총평점평균");
                // C형처럼 줄 맨 앞의 "평점평균"도 누적으로 본다 (학기 소계 줄은 "취득학점"으로 시작)
                boolean leadingGpa = i == 0 && isLabel(text, "평점평균");
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
            }
        }
        return total;
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

    /** 라벨 바로 오른쪽(같은 줄, 가까운 거리)의 숫자. "/"를 만나면 그 뒤는 만점이라 멈춘다 */
    private static Found numberAfter(List<OcrPage.Word> line, int labelIndex) {
        OcrPage.Word label = line.get(labelIndex);
        // "취득학점:14.0"처럼 라벨과 숫자가 붙어 읽힌 경우
        Found attached = attachedNumber(label.text(), label.confidence());
        if (attached != null) {
            return attached;
        }
        for (int i = labelIndex + 1; i < line.size(); i++) {
            OcrPage.Word w = line.get(i);
            if (w.x0() - label.x1() > 200 || w.text().equals("/")) {
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
        // "행정평균"처럼 앞 두 글자가 모두 틀려도 "□□평균" 모양이면 평점평균으로 본다 ("평점환산"은 아님)
        if (label.equals("평점평균") && cleaned.substring(0, 4).endsWith("평균")) {
            return true;
        }
        int allowed = label.length() >= 5 ? 2 : label.length() == 4 ? 1 : 0;
        return distance(cleaned.substring(0, label.length()), label) <= allowed;
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

    private static int column(OcrPage.Word word, double middle) {
        return word.x0() < middle ? 0 : 1;
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
    }
}
