package kr.co.ktscholarship.backend.scholar;

import java.util.Locale;
import java.util.Objects;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.UserStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ScholarRegistrationRepository {

    private final JdbcClient jdbc;

    ScholarRegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // 계정과 프로필을 함께 저장하는 등록 트랜잭션에서만 호출한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertAccount(String name, String email, String passwordHash, UserStatus status) {
        var key = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO users (name, email, password_hash, role, status, password_change_required)
                    VALUES (:name, :email, :passwordHash, 'SCHOLAR', :status, TRUE)
                    """)
                .param("name", name)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("status", status.name())
                .update(key, "id");
        } catch (DuplicateKeyException ex) {
            String message = ex.getMostSpecificCause().getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("uk_users_active_email")) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw ex;
        }
        return Objects.requireNonNull(key.getKey(), "등록한 계정 ID가 없습니다.").longValue();
    }
}
