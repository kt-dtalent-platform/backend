package kr.co.ktscholarship.backend.submission;

import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionHistoryService {
    private final SubmissionHistoryRepository repository;
    public SubmissionHistoryService(SubmissionHistoryRepository repository) { this.repository=repository; }
    @Transactional(readOnly=true)
    public HistoryPage find(long user,String status,int page,int size) {
        if(status!=null && !List.of("DRAFT","SUBMITTED","REVISION_REQUIRED","RESUBMITTED","CONFIRMED").contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        long total=repository.count(user,status);
        return new HistoryPage(repository.items(user,status,page,size),page,size,total);
    }
    public record HistoryPage(List<SubmissionHistoryRepository.HistoryEntry> items,int page,int pageSize,long total) {}
}
