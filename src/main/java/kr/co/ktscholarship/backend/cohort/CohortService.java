package kr.co.ktscholarship.backend.cohort;

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

    public List<CohortItem> findByProgramId(long programId) {
        if (!repository.existsProgram(programId)) {
            throw new BusinessException(ErrorCode.PROGRAM_NOT_FOUND);
        }
        return repository.findByProgramId(programId);
    }
}
