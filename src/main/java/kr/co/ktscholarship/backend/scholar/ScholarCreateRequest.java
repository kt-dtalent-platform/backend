package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ScholarCreateRequest(
    @NotNull @Positive Long cohortId,
    @NotBlank @Size(max = 50) String name,
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 20) String phone,
    @NotBlank @Size(max = 100) String university,
    @Size(max = 100) String major,
    @Size(max = 30) String studentNo
) {
}
