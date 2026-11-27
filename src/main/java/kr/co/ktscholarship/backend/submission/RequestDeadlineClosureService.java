package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableScheduling
public class RequestDeadlineClosureService {
    private final RequestDeadlineClosureRepository repository;
    public RequestDeadlineClosureService(RequestDeadlineClosureRepository repository) { this.repository = repository; }

    @Transactional
    public Closure close(long id,long actor) {
        String status=repository.lockStatus(id)
            .orElseThrow(()->new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if (status.equals("CLOSED")) return new Closure(id,"CLOSED");
        if (!status.equals("IN_PROGRESS")) throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
        Instant now=Instant.now();
        repository.close(id,now);
        repository.audit(id,actor,now);
        return new Closure(id,"CLOSED");
    }
    public record Closure(long requestId,String status) {}

    @Scheduled(initialDelay=60000, fixedDelay=60000)
    @Transactional
    public void closeExpired() {
        repository.closeExpired(Instant.now());
    }
}
