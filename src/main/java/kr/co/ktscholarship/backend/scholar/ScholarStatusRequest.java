package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.constraints.*;

public record ScholarStatusRequest(
    @NotNull Status status,
    @Min(1) @Max(9999) Integer completedYear,
    Reason withdrawalReason,
    @Size(max = 500) String withdrawalReasonDetail
) {
    public enum Status { ENROLLED, LEAVE, COMPLETED, WITHDRAWN }
    public enum Reason { ACADEMIC_STATUS, DOUBLE_BENEFIT, GRADE_NOT_MET, ACTIVITY_NOT_MET, VOLUNTARY, OTHER }
}
