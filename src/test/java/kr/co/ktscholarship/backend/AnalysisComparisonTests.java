package kr.co.ktscholarship.backend;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import kr.co.ktscholarship.backend.api.AnalysisService;
import org.junit.jupiter.api.Test;

// 문서 비교 검증
class AnalysisComparisonTests {
  // 입력 성적
  private final Map<String, Object> entered = Map.of("gpa", 4.1, "gpa_scale", 4.5, "credits", 18);

  // 추출값 예제
  private Map<String, Object> extracted() {
    var m = new HashMap<String, Object>(entered);
    m.put("school", "대학교");
    m.put("documentType", "GRADE_CERTIFICATE");
    m.put("academicYear", 2026);
    m.put("semester", "1");
    m.put("hasF", false);
    return m;
  }

  @Test
  // 전체 항목 일치 검증
  void onlyCompleteMatchingEvidenceIsMatch() {
    assertThat(AnalysisService.compare(entered, extracted(), 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("MATCH");
  }

  @Test
  // F 학점 수동 확인 검증
  void fDetectedOrUnknownCannotPass() {
    var m = extracted();
    m.put("hasF", true);
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("UNREADABLE");
    m.remove("hasF");
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("UNREADABLE");
  }

  @Test
  // 누락 숫자 검증
  void missingOrInvalidNumbersNeverMatch() {
    var m = extracted();
    m.remove("credits");
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("UNREADABLE");
    m.put("credits", -1);
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("UNREADABLE");
  }

  @Test
  // 학기 평점 불일치 검증
  void wrongSemesterAndGpaAreMismatches() {
    var m = extracted();
    m.put("semester", "2");
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("MISMATCH");
    m = extracted();
    m.put("gpa", 3.9);
    assertThat(AnalysisService.compare(entered, m, 2026, "1", "대학교").get("overallResult"))
        .isEqualTo("MISMATCH");
  }

  @Test
  // 학교 불일치 검증
  void wrongSchoolRequiresManualReview() {
    assertThat(
            AnalysisService.compare(entered, extracted(), 2026, "1", "다른대학교").get("overallResult"))
        .isEqualTo("MISMATCH");
  }
}
