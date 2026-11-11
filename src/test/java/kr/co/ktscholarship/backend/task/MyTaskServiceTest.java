package kr.co.ktscholarship.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MyTaskServiceTest {
    @ParameterizedTest
    @CsvSource({
        "2026-11-19T14:59:59Z, 1",
        "2026-11-19T15:00:00Z, 0",
        "2026-11-19T23:00:00Z, 0",
        "2026-11-20T15:00:00Z, -1"
    })
    void calculatesDaysAtSeoulMidnightRegardlessOfUtcDate(String now, long expected) {
        var repository = mock(MyTaskRepository.class);
        var due = Instant.parse("2026-11-20T14:59:00Z");
        when(repository.find(1, MyTaskRepository.Filter.TODO, MyTaskRepository.Sort.DUE_ASC))
            .thenReturn(List.of(new MyTaskRepository.TaskRow(2, "제출", java.time.LocalDateTime.ofInstant(due, ZoneOffset.UTC), "NOT_SUBMITTED", null)));
        var service = new MyTaskService(repository, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
        var item = service.find(1, MyTaskRepository.Filter.TODO, MyTaskRepository.Sort.DUE_ASC).items().get(0);
        assertThat(item.dDay()).isEqualTo(expected);
        assertThat(item.dueAt()).isEqualTo(java.time.LocalDateTime.ofInstant(due, ZoneOffset.UTC));
    }
}
