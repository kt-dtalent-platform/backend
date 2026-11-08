package kr.co.ktscholarship.backend.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    // 삭제되지 않은 계정만 조회. email에 UNIQUE가 없어 중복될 수 있으므로 List로 받는다
    List<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);
}
