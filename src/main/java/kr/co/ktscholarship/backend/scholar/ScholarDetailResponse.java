package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.media.Schema;

public record ScholarDetailResponse(
    long id, String name, String email, String maskedPhone, String university,
    String major, Cohort cohort, String status,
    @Schema(description = "계정 상태. 장학생 상태(status)와 별개", allowableValues = {"ACTIVE", "INACTIVE", "LOCKED"})
    String accountStatus, SubmissionSummary submissionSummary,
    Integer completedYear, String withdrawalReason, String withdrawalReasonDetail,
    ScholarBankAccount bankAccount, Long schoolId
) {
    public record SubmissionSummary(long total, long confirmed, long pending) {
    }

    public record Cohort(long id, String name) {
    }
}
