package kr.co.ktscholarship.backend.cohort;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CohortCreateRequest(@NotBlank @Size(max = 50) String name, LocalDate startedOn, LocalDate endedOn) {
}
