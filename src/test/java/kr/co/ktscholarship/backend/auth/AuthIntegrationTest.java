package kr.co.ktscholarship.backend.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthIntegrationTest.TestOnlyController.class)
class AuthIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final String ISSUER = "kt-scholarship-backend";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @BeforeEach
    void setUpUsers() {
        String hash = passwordEncoder.encode(PASSWORD);
        insertUser(1L, "manager@kt.com", hash, "담당자", "MANAGER", "ACTIVE", false);
        insertUser(2L, "scholar@kt.com", hash, "장학생", "SCHOLAR", "ACTIVE", false);
        insertUser(3L, "inactive@kt.com", hash, "비활성", "SCHOLAR", "INACTIVE", false);
        insertUser(4L, "locked@kt.com", hash, "잠김", "SCHOLAR", "LOCKED", false);
        insertUser(5L, "deleted@kt.com", hash, "삭제됨", "SCHOLAR", "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        void 성공하면_토큰과_역할과_이름을_반환한다() throws Exception {
            login("manager@kt.com", PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                    .andExpect(jsonPath("$.success.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.success.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.success.data.name").value("담당자"))
                    .andExpect(jsonPath("$.meta.path").value("/v1/auth/login"));
        }

        @Test
        void 비밀번호가_틀리면_401_LOGIN_FAILED() throws Exception {
            expectLoginFailed(login("manager@kt.com", "wrong-password"));
        }

        @Test
        void 없는_이메일도_같은_401_LOGIN_FAILED() throws Exception {
            expectLoginFailed(login("nobody@kt.com", PASSWORD));
        }

        @Test
        void 삭제된_계정은_없는_계정과_같이_401() throws Exception {
            expectLoginFailed(login("deleted@kt.com", PASSWORD));
        }

        @Test
        void 비밀번호가_틀리면_잠긴_계정도_상태를_드러내지_않는다() throws Exception {
            expectLoginFailed(login("locked@kt.com", "wrong-password"));
        }

        @Test
        void 비활성_계정은_403_ACCOUNT_DISABLED() throws Exception {
            login("inactive@kt.com", PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        }

        @Test
        void 잠긴_계정은_423_ACCOUNT_LOCKED() throws Exception {
            login("locked@kt.com", PASSWORD)
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
        }

        @Test
        void 요청값이_잘못되면_400() throws Exception {
            mockMvc.perform(post("/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        private void expectLoginFailed(org.springframework.test.web.servlet.ResultActions result) throws Exception {
            result.andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.resultType").value("FAIL"))
                    .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"))
                    .andExpect(jsonPath("$.error.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @Nested
    @DisplayName("토큰 검증 (401)")
    class TokenValidation {

        @Test
        void 토큰이_없으면_401_UNAUTHORIZED() throws Exception {
            expectUnauthorized(mockMvc.perform(get("/v1/me")));
        }

        @Test
        void 형식이_잘못된_토큰은_401() throws Exception {
            expectUnauthorized(mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")));
        }

        @Test
        void 다른_키로_서명한_토큰은_401() throws Exception {
            byte[] otherKey = Base64.getDecoder().decode("b3RoZXIta2V5LW90aGVyLWtleS1vdGhlci1rZXktMzI=");
            JwtEncoder otherEncoder = new NimbusJwtEncoder(
                    new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
            String forged = encode(otherEncoder, "1", "MANAGER", Instant.now().plusSeconds(600));
            expectUnauthorized(mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)));
        }

        @Test
        void 만료된_토큰은_401() throws Exception {
            // 검증기는 시계 오차로 60초를 허용하므로 그보다 오래 지난 토큰을 쓴다
            String expired = encode(jwtEncoder, "1", "MANAGER", Instant.now().minusSeconds(120));
            expectUnauthorized(mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)));
        }

        private void expectUnauthorized(org.springframework.test.web.servlet.ResultActions result) throws Exception {
            result.andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                    .andExpect(jsonPath("$.resultType").value("FAIL"))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.meta.path").value("/v1/me"));
        }
    }

    @Nested
    @DisplayName("역할 검사 (403)")
    class RoleCheck {

        @Test
        void 장학생이_관리자_API를_호출하면_403_ACCESS_DENIED() throws Exception {
            mockMvc.perform(get("/v1/scholars/test-only").header(HttpHeaders.AUTHORIZATION, bearer("2", "SCHOLAR")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.resultType").value("FAIL"))
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }

        @Test
        void 담당자는_관리자_API를_호출할_수_있다() throws Exception {
            mockMvc.perform(get("/v1/scholars/test-only").header(HttpHeaders.AUTHORIZATION, bearer("1", "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success.data").value("manager-ok"));
        }

        @Test
        void 담당자가_장학생_전용_API를_호출하면_403() throws Exception {
            mockMvc.perform(get("/v1/me/test-only").header(HttpHeaders.AUTHORIZATION, bearer("1", "MANAGER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }

        @Test
        void 메서드_권한_검사에서_거절되면_500이_아니라_403() throws Exception {
            mockMvc.perform(get("/v1/submissions/test-only").header(HttpHeaders.AUTHORIZATION, bearer("2", "SCHOLAR")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }
    }

    @Nested
    @DisplayName("내 정보")
    class Me {

        @Test
        void 로그인으로_받은_토큰으로_내_정보를_조회한다() throws Exception {
            String body = login("scholar@kt.com", PASSWORD).andReturn().getResponse().getContentAsString();
            String token = com.jayway.jsonpath.JsonPath.read(body, "$.success.data.accessToken");

            mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success.data.id").value(2))
                    .andExpect(jsonPath("$.success.data.name").value("장학생"))
                    .andExpect(jsonPath("$.success.data.email").value("scholar@kt.com"))
                    .andExpect(jsonPath("$.success.data.role").value("SCHOLAR"))
                    .andExpect(jsonPath("$.success.data.cohort").doesNotExist())
                    .andExpect(jsonPath("$.success.data.accessiblePrograms").doesNotExist());
        }

        @Test
        void 토큰_발급_후_삭제된_계정은_401() throws Exception {
            mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, bearer("5", "SCHOLAR")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("API 문서")
    class ApiDocs {

        @Test
        void 토큰_없이_열람되고_Bearer_스키마가_있다() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                    .andExpect(jsonPath("$.paths['/v1/auth/login'].post.security").isEmpty());
        }
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private String bearer(String userId, String role) {
        return "Bearer " + encode(jwtEncoder, userId, role, Instant.now().plusSeconds(600));
    }

    private String encode(JwtEncoder encoder, String userId, String role, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(expiresAt.minusSeconds(1800))
                .expiresAt(expiresAt)
                .subject(userId)
                .claim("role", role)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void insertUser(Long id, String email, String hash, String name, String role, String status, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, name, role, status, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, email, hash, name, role, status, deleted ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    // 아직 실제 관리자·혼합 API가 없어 권한 규칙 확인용으로 테스트에서만 등록하는 Controller
    @RestController
    static class TestOnlyController {

        @GetMapping("/v1/scholars/test-only")
        ApiResponse<String> managerOnly() {
            return ApiResponse.success("manager-ok", "/v1/scholars/test-only");
        }

        @GetMapping("/v1/me/test-only")
        ApiResponse<String> scholarOnly() {
            return ApiResponse.success("scholar-ok", "/v1/me/test-only");
        }

        @PreAuthorize("hasRole('MANAGER')")
        @GetMapping("/v1/submissions/test-only")
        ApiResponse<String> methodSecured() {
            return ApiResponse.success("method-ok", "/v1/submissions/test-only");
        }
    }
}
