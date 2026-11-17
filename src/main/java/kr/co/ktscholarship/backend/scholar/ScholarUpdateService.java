package kr.co.ktscholarship.backend.scholar;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarUpdateService {
    private final ScholarUpdateRepository repository;
    private final ScholarSchoolService schoolService;

    public ScholarUpdateService(ScholarUpdateRepository repository, ScholarSchoolService schoolService) {
        this.repository = repository;
        this.schoolService = schoolService;
    }

    @Transactional
    public ScholarUpdateResponse update(long scholarId, ScholarUpdateRequest request) {
        if (!repository.lockExists(scholarId)) {
            throw new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND);
        }
        String schoolName = schoolService.resolveName(request.schoolId(), request.university());
        Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.update(scholarId, request, schoolName, updatedAt);
        return new ScholarUpdateResponse(scholarId, updatedAt);
    }
}
