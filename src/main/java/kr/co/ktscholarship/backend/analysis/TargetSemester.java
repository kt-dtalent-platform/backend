package kr.co.ktscholarship.backend.analysis;

/**
 * 비교할 학기. requests.academic_year, requests.semester('1', '2', 'SUMMER', 'WINTER')와 같은 값.
 */
public record TargetSemester(int year, String semester) {
}
