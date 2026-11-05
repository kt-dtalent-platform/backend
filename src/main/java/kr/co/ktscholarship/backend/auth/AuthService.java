package kr.co.ktscholarship.backend.auth;

import kr.co.ktscholarship.backend.auth.dto.LoginRequest;
import kr.co.ktscholarship.backend.auth.dto.LoginResponse;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.auth.jwt.JwtTokenProvider;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.User;
import kr.co.ktscholarship.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    // 없는 이메일일 때도 해시 비교를 한 번 수행해 응답 시간 차이를 줄인다
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
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
        return MeResponse.from(user);
    }
}
