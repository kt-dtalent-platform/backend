package kr.co.ktscholarship.backend.scholar;

import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScholarListService {
    private final ScholarRepository repository;

    public ScholarListService(ScholarRepository repository) {
        this.repository = repository;
    }

    public ScholarListResponse findAll(Long cohortId, String status, String keyword,
        Long requestId, String submitStatus, int page, int pageSize) {
        if ((requestId == null) != (submitStatus == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "requestId와 submitStatus를 함께 지정해 주세요.");
        }
        return repository.findAll(cohortId, status, keyword, requestId, submitStatus, page, pageSize);
    }
}
