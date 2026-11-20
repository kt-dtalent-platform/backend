package kr.co.ktscholarship.backend.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import kr.co.ktscholarship.backend.auth.jwt.JwtProperties;
import kr.co.ktscholarship.backend.auth.jwt.JwtTokenProvider;
import kr.co.ktscholarship.backend.user.User;
import org.springframework.stereotype.Component;

/**
 * access token(JWT, 짧게)과 refresh token(임의 문자열, 길게)을 한 쌍으로 발급한다.
 * refresh token은 DB 기록으로만 확인하므로 JWT일 필요가 없다
 */
@Component
public class TokenIssuer {

    private final SecureRandom random = new SecureRandom();
    private final JwtTokenProvider jwt;
    private final JwtProperties properties;
    private final RefreshTokenRepository refreshTokens;

    public TokenIssuer(JwtTokenProvider jwt, JwtProperties properties, RefreshTokenRepository refreshTokens) {
        this.jwt = jwt;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
    }

    /** 호출하는 쪽의 트랜잭션 안에서 refresh token을 저장한다 */
    public Pair issue(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        refreshTokens.insert(user.getId(), hash(refreshToken), now.plus(properties.refreshTokenExpiration()), now);
        return new Pair(jwt.createAccessToken(user), refreshToken);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }

    public record Pair(String accessToken, String refreshToken) {
    }
}
