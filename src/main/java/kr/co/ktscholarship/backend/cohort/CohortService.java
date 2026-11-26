package kr.co.ktscholarship.backend.cohort;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CohortService {

    private final CohortRepository repository;

    public CohortService(CohortRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CohortItem create(long programId, CohortCreateRequest request) {
        if (!repository.existsProgram(programId)) {
            throw new BusinessException(ErrorCode.PROGRAM_NOT_FOUND);
        }
        if (request.startedOn() != null && request.endedOn() != null
                && request.endedOn().isBefore(request.startedOn())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, List.of("endedOn"));
        }
        long id;
        try {
            id = repository.insert(programId, request);
        } catch (DuplicateKeyException exception) {
            String message = exception.getMostSpecificCause().getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_cohorts_program_name")) {
                throw new BusinessException(ErrorCode.DUPLICATE_COHORT);
            }
            throw exception;
        }
        return new CohortItem(id, request.name().strip(), request.startedOn(), request.endedOn(), 0, 0);
    }

    public List<CohortItem> findByProgramId(long programId) {
        if (!repository.existsProgram(programId)) {
            throw new BusinessException(ErrorCode.PROGRAM_NOT_FOUND);
        }
        return repository.findByProgramId(programId);
    }
}
