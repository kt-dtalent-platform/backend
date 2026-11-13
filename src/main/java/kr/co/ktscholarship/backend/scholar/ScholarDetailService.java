package kr.co.ktscholarship.backend.scholar;

import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScholarDetailService {
    private final ScholarDetailRepository repository;

    public ScholarDetailService(ScholarDetailRepository repository) {
        this.repository = repository;
    }

    public ScholarDetailResponse findById(long scholarId) {
        return repository.findById(scholarId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND));
    }
}
