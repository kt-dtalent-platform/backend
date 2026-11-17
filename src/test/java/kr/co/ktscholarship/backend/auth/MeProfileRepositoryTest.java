package kr.co.ktscholarship.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MeProfileRepositoryTest {
    @Autowired JdbcClient jdbc;
    @Autowired MeProfileRepository profiles;
    @Autowired UserRepository users;
    @BeforeEach void seed() {
        jdbc.sql("INSERT INTO programs(id,name) VALUES(82001,'내 정보 검증')").update();
        jdbc.sql("INSERT INTO cohorts(id,program_id,name) VALUES(82001,82001,'2026기')").update();
        jdbc.sql("""
            INSERT INTO users(id,name,email,password_hash,role,deleted_at) VALUES
            (82001,'학생','me-school@example.test','test','SCHOLAR',NULL),
            (82002,'담당자','me-school-manager@example.test','test','MANAGER',NULL),
            (82003,'탈퇴','me-school-deleted@example.test','test','SCHOLAR','2026-01-01')
            """).update();
        jdbc.sql("""
            INSERT INTO scholar_profiles(user_id,cohort_id,school_name)
            VALUES(82001,82001,'고려대학교 (세종캠퍼스)'),(82003,82001,'학교')
            """).update();
    }
    @Test void readsCohortAndSchoolWithoutChangingManagerContract() {
        var student=MeResponse.from(users.findById(82001L).orElseThrow(),null,profiles.find(82001).orElseThrow());
        assertThat(student.cohort()).isEqualTo(new MeResponse.Cohort(82001L,"2026기"));
        assertThat(student.university()).isEqualTo("고려대학교 (세종캠퍼스)");
        assertThat(student.accessiblePrograms()).isNull();
        var programs=List.of(new MeResponse.Program(82001L,"내 정보 검증"));
        var manager=MeResponse.from(users.findById(82002L).orElseThrow(),programs);
        assertThat(manager.accessiblePrograms()).isEqualTo(programs);
        assertThat(manager.cohort()).isNull();
        assertThat(manager.university()).isNull();
    }
    @Test void missingManagerAndDeletedProfilesAreAbsent() {
        assertThat(profiles.find(999999)).isEmpty();
        assertThat(profiles.find(82002)).isEmpty();
        assertThat(profiles.find(82003)).isEmpty();
    }
}
