package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableScheduling
public class RequestDeadlineClosureService {
    private final RequestDeadlineClosureRepository repository;
    public RequestDeadlineClosureService(RequestDeadlineClosureRepository repository) { this.repository = repository; }

    @Scheduled(initialDelay=60000, fixedDelay=60000)
    @Transactional
    public void closeExpired() {
        repository.closeExpired(Instant.now());
    }
}
