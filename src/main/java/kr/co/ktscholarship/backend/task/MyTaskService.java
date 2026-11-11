package kr.co.ktscholarship.backend.task;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MyTaskService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final MyTaskRepository repository;
    private final Clock clock;

    @Autowired
    public MyTaskService(MyTaskRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MyTaskService(MyTaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 응답 시각은 UTC로 유지하고 D-day는 한국의 오늘과 마감 날짜로 계산한다. */
    public MyTaskResponse find(long userId, MyTaskRepository.Filter status, MyTaskRepository.Sort sort) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        var items = repository.find(userId, status, sort).stream()
            .map(task -> new MyTaskResponse.Item(
                task.requestId(), task.title(), task.requestDueAt(),
                ChronoUnit.DAYS.between(today, task.requestDueAt().atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(SEOUL).toLocalDate()),
                task.status(), task.revisionReason()
            )).toList();
        return new MyTaskResponse(items);
    }
}
