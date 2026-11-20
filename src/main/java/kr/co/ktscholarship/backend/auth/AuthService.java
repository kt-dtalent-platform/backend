package kr.co.ktscholarship.backend.auth;

import kr.co.ktscholarship.backend.auth.dto.LoginRequest;
import kr.co.ktscholarship.backend.auth.dto.LoginResponse;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.auth.dto.PasswordChangeRequest;
import kr.co.ktscholarship.backend.auth.dto.PasswordChangeResponse;
import kr.co.ktscholarship.backend.auth.dto.TokenResponse;
import kr.co.ktscholarship.backend.auth.token.RefreshTokenRepository;
import kr.co.ktscholarship.backend.auth.token.TokenIssuer;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.User;
import kr.co.ktscholarship.backend.user.UserRepository;
import kr.co.ktscholarship.backend.user.UserRole;
import kr.co.ktscholarship.backend.user.UserStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenRepository refreshTokens;
    private final MeProgramRepository meProgramRepository;
    private final MeProfileRepository meProfileRepository;
    // 없는 이메일일 때도 해시 비교를 한 번 수행해 응답 시간 차이를 줄인다
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            RefreshTokenRepository refreshTokens,
            MeProgramRepository meProgramRepository,
            MeProfileRepository meProfileRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
        this.meProgramRepository = meProgramRepository;
        this.meProfileRepository = meProfileRepository;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
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

        TokenIssuer.Pair tokens = tokenIssuer.issue(user);
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), user.getRole().name(), user.getName(),
                user.isPasswordChangeRequired());
    }

    /**
     * refresh token으로 새 쌍을 준다. 쓴 토큰은 바로 폐기한다(회전).
     * 이미 폐기된 토큰이 다시 오면 탈취를 의심해 그 사용자의 refresh token을 모두 폐기한다.
     * 실패해도 폐기한 기록은 남아야 해서 BusinessException으로는 되돌리지 않는다
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenRepository.Stored stored = refreshTokens.lock(TokenIssuer.hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (stored.revokedAt() != null) {
            log.warn("폐기된 refresh token 재사용 감지. userId={}", stored.userId());
            refreshTokens.revokeAll(stored.userId(), now);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!Instant.now().isBefore(stored.expiresAt())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        refreshTokens.revoke(stored.id(), now);
        // 삭제·사용 중지된 계정, 비밀번호를 바꾸기 전에 받은 토큰은 이어 주지 않는다 (잠김은 새 로그인만 막음)
        User user = userRepository.findByIdAndDeletedAtIsNull(stored.userId())
                .filter(found -> found.getStatus() != UserStatus.INACTIVE)
                .filter(found -> found.getTokensValidAfter() == null
                        || !stored.createdAt().isBefore(found.getTokensValidAfter()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        TokenIssuer.Pair tokens = tokenIssuer.issue(user);
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 이 기기의 refresh token만 지운다. 없는 토큰이어도 같은 응답(로그아웃은 항상 성공).
     * 폐기 표시가 아니라 삭제라서, 로그아웃한 토큰이 다시 와도 재사용 감지로 다른 기기까지 끊지 않는다
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.lock(TokenIssuer.hash(refreshToken))
                .filter(stored -> stored.revokedAt() == null)
                .ifPresent(stored -> refreshTokens.delete(stored.id()));
    }

    @Transactional
    public PasswordChangeResponse changePassword(Long userId, PasswordChangeRequest request) {
        // 토큰 발급 후 삭제된 계정은 인증 실패로 처리
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        // 해시는 매번 달라서 문자열 비교가 아니라 matches로 확인한다
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.SAME_AS_CURRENT);
        }

        // DATETIME이 초 단위라 저장 전에 맞춰 둔다. 응답 값과 DB 값이 같아진다
        Instant changedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.changePassword(passwordEncoder.encode(request.newPassword()), changedAt);
        // 다른 기기의 세션은 모두 끊고(삭제), 이 기기에서 계속 쓸 새 토큰을 준다
        refreshTokens.deleteAll(user.getId());
        TokenIssuer.Pair tokens = tokenIssuer.issue(user);
        return new PasswordChangeResponse(changedAt, tokens.accessToken(), tokens.refreshToken());
    }

    public MeResponse getMe(Long userId) {
        // 토큰 발급 후 삭제된 계정은 인증 실패로 처리
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        List<MeResponse.Program> programs = user.getRole() == UserRole.MANAGER ? meProgramRepository.findAll() : null;
        // 장학생만 기수·학교를 채운다. 프로필이 아직 없으면 둘 다 null (화면은 "없음")
        MeResponse.ScholarProfile profile = user.getRole() == UserRole.SCHOLAR
                ? meProfileRepository.find(userId).orElse(null)
                : null;
        return MeResponse.from(user, programs, profile);
    }
}
