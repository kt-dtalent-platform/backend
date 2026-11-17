package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TargetDeadlineService {
    private final TargetDeadlineRepository repository;
    public TargetDeadlineService(TargetDeadlineRepository repository) { this.repository=repository; }

    @Transactional(readOnly=true)
    public Deadline find(long request,long scholar) {
        var row=target(request,scholar,false);
        return new Deadline(request,scholar,row.commonDueAt(),row.dueAt());
    }

    @Transactional
    public Deadline extend(long request,long scholar,Instant dueAt,long actor) {
        var row=target(request,scholar,true);
        Instant due=dueAt.truncatedTo(ChronoUnit.SECONDS), now=Instant.now();
        if(!row.published() || !row.status().equals("IN_PROGRESS")) throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
        if(!due.isAfter(now) || !due.isAfter(row.dueAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"현재 유효 마감일보다 뒤인 미래 시각을 입력해 주세요.");
        }
        repository.extend(row.id(),due,actor,now.truncatedTo(ChronoUnit.SECONDS));
        return new Deadline(request,scholar,row.commonDueAt(),due);
    }
    private TargetDeadlineRepository.Target target(long request,long scholar,boolean lock) {
        return repository.find(request,scholar,lock).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    public record Deadline(long requestId,long scholarId,Instant commonDueAt,Instant dueAt) {}
}
