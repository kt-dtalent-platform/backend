package kr.co.ktscholarship.backend.scholar;

import java.util.List;
import jakarta.validation.constraints.*;

public record ScholarBulkStatusRequest(
    @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> scholarIds,
    @NotNull ScholarStatusRequest.Status status,
    @Min(1) @Max(9999) Integer completedYear,
    ScholarStatusRequest.Reason withdrawalReason,
    @Size(max = 500) String withdrawalReasonDetail
) {
    ScholarStatusRequest change() {
        return new ScholarStatusRequest(status, completedYear, withdrawalReason, withdrawalReasonDetail);
    }
}
