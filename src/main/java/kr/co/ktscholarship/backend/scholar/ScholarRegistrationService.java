package kr.co.ktscholarship.backend.scholar;

import java.security.SecureRandom;
import java.util.Base64;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarRegistrationService {
    private final ScholarRegistrationRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public ScholarRegistrationService(ScholarRegistrationRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 모든 기수 등록을 허용하고 계정과 프로필을 한 트랜잭션으로 저장한다. */
    @Transactional
    public ScholarCreateResponse register(ScholarCreateRequest request) {
        if (!repository.cohortExists(request.cohortId())) {
            throw new BusinessException(ErrorCode.COHORT_NOT_FOUND);
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String temporaryPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long id;
        try {
            id = repository.insertAccount(request.name(), request.email(),
                passwordEncoder.encode(temporaryPassword), UserStatus.ACTIVE);
        } catch (DuplicateKeyException exception) {
            if (repository.isActiveEmailConflict(exception)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw exception;
        }
        repository.insertProfile(id, request);
        return new ScholarCreateResponse(id, UserStatus.ACTIVE.name(), temporaryPassword);
    }
}
