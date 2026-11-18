package kr.co.ktscholarship.backend.scholar;

import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.school.SchoolRepository;
import org.springframework.stereotype.Service;

@Service
public class ScholarSchoolService {
    private final SchoolRepository repository;

    public ScholarSchoolService(SchoolRepository repository) {
        this.repository = repository;
    }

    public String resolveName(Long schoolId, String university) {
        if (schoolId == null) {
            return university;
        }
        return repository.findNameById(schoolId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, List.of("schoolId")));
    }
}
