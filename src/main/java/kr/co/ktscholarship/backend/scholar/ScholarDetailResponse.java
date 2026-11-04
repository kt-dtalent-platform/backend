package kr.co.ktscholarship.backend.scholar;

public record ScholarDetailResponse(
    long id, String name, String email, String maskedPhone, String university,
    String major, Cohort cohort, String status
) {
    public record Cohort(long id, String name) {
    }
}
