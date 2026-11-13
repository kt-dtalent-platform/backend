package kr.co.ktscholarship.backend.cohort;

import java.time.LocalDate;

public record CohortItem(
        long id, String name, LocalDate startedOn, LocalDate endedOn, long scholarCount
) {
}
