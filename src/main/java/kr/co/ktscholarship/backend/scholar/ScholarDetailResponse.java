package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.media.Schema;

public record ScholarDetailResponse(
    long id, String name, String email, String maskedPhone, String university,
    String major, Cohort cohort, String status,
    @Schema(description = "계정 상태. 장학생 상태(status)와 별개", allowableValues = {"ACTIVE", "INACTIVE", "LOCKED"})
    String accountStatus, SubmissionSummary submissionSummary,
    Integer completedYear, String withdrawalReason, String withdrawalReasonDetail,
    ScholarBankAccount bankAccount, Long schoolId,
    @Schema(description = "담당자 수정 폼에 사용하는 전체 연락처") String phone
) {
    public record SubmissionSummary(long total, long confirmed, long pending) {
    }

    public record Cohort(long id, String name) {
    }
}
