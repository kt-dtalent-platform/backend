package kr.co.ktscholarship.backend.report;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagerDashboardService {
    private final ManagerDashboardRepository repository;
    public ManagerDashboardService(ManagerDashboardRepository repository) { this.repository=repository; }
    @Transactional(readOnly=true)
    public Dashboard find(Long program,Long cohort) {
        var counts=repository.counts(program,cohort);
        var oldest=repository.oldest(program,cohort).orElse(null);
        var review=new Review(counts.stream().mapToLong(ManagerDashboardRepository.RequestCounts::waiting).sum(),
            counts.stream().mapToLong(ManagerDashboardRepository.RequestCounts::resubmitted).sum(),
            oldest==null?null:oldest.id(),oldest==null?null:oldest.submittedAt());
        return new Dashboard(review,counts.stream().map(c->new RequestSummary(c.id(),c.title(),c.closesAt(),c.targets(),
            c.confirmed(),c.waiting(),c.revision(),c.targets()-c.confirmed()-c.waiting()-c.revision())).toList());
    }
    public record Dashboard(Review review,List<RequestSummary> requests) {}
    public record Review(long waiting,long resubmitted,Long oldestSubmissionId,Instant oldestSubmittedAt) {}
    public record RequestSummary(long requestId,String title,Instant closesAt,long targetCount,long confirmed,long waiting,long revisionRequired,long notSubmitted) {}
}
