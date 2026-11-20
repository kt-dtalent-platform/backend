package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ScholarBulkCreateRequest(
    @NotNull @Positive Long cohortId,
    @NotEmpty @Size(max = 500) @Valid List<@NotNull Scholar> scholars
) {
    public record Scholar(String name, String email, String university, String phone,
                          String major, String studentNo, Long schoolId) {
        ScholarCreateRequest inCohort(long cohortId) {
            return new ScholarCreateRequest(cohortId, name, email, phone, university, major, studentNo, schoolId);
        }
    }
}
