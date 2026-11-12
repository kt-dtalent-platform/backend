package kr.co.ktscholarship.backend.scholar;

import java.util.Locale;
import java.util.Objects;
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

    public boolean cohortExists(long cohortId) {
        return jdbc.sql("SELECT COUNT(*) FROM cohorts WHERE id = :id")
            .param("id", cohortId).query(Long.class).single() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertProfile(long userId, ScholarCreateRequest request) {
        jdbc.sql("""
                INSERT INTO scholar_profiles (user_id, cohort_id, school_name, phone, major, student_no)
                VALUES (:userId, :cohortId, :school, :phone, :major, :studentNo)
                """)
            .param("userId", userId).param("cohortId", request.cohortId())
            .param("school", request.university()).param("phone", request.phone())
            .param("major", request.major()).param("studentNo", request.studentNo()).update();
    }

    // 계정과 프로필을 함께 저장하는 등록 트랜잭션에서만 호출한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertAccount(String name, String email, String passwordHash, UserStatus status) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO users (name, email, password_hash, role, status, password_change_required)
                VALUES (:name, :email, :passwordHash, 'SCHOLAR', :status, TRUE)
                """)
            .param("name", name)
            .param("email", email)
            .param("passwordHash", passwordHash)
            .param("status", status.name())
            .update(key, "id");
        return Objects.requireNonNull(key.getKey(), "등록한 계정 ID가 없습니다.").longValue();
    }

    public boolean isActiveEmailConflict(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("uk_users_active_email");
    }
}
