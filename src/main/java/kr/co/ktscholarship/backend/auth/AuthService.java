package kr.co.ktscholarship.backend.auth;

import kr.co.ktscholarship.backend.auth.dto.LoginRequest;
import kr.co.ktscholarship.backend.auth.dto.LoginResponse;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.auth.jwt.JwtTokenProvider;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.User;
import kr.co.ktscholarship.backend.user.UserRepository;
import kr.co.ktscholarship.backend.user.UserRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final MeProgramRepository meProgramRepository;
    // 없는 이메일일 때도 해시 비교를 한 번 수행해 응답 시간 차이를 줄인다
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            MeProgramRepository meProgramRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.meProgramRepository = meProgramRepository;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public LoginResponse login(LoginRequest request) {
        List<User> users = userRepository.findByEmailAndDeletedAtIsNull(request.email());
        if (users.size() > 1) {
            // 어느 계정이 맞는지 알 수 없으므로 로그인을 막고 데이터 정리를 위해 기록한다
            log.warn("중복 이메일 계정 {}개로 로그인 시도. userIds={}",
                    users.size(), users.stream().map(User::getId).toList());
        }
        if (users.size() != 1) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        User user = users.get(0);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // 계정 상태는 비밀번호가 맞았을 때만 알려 준다
        switch (user.getStatus()) {
            case INACTIVE -> throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
            case LOCKED -> throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            case ACTIVE -> { }
        }

        String accessToken = jwtTokenProvider.createAccessToken(user);
        return new LoginResponse(accessToken, user.getRole().name(), user.getName());
    }

    public MeResponse getMe(Long userId) {
        // 토큰 발급 후 삭제된 계정은 인증 실패로 처리
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return MeResponse.from(user, user.getRole() == UserRole.MANAGER ? meProgramRepository.findAll() : null);
    }
}
