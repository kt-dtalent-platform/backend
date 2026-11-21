package kr.co.ktscholarship.backend.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.co.ktscholarship.backend.auth.dto.LoginRequest;
import kr.co.ktscholarship.backend.auth.dto.LoginResponse;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.auth.dto.PasswordChangeRequest;
import kr.co.ktscholarship.backend.auth.dto.PasswordChangeResponse;
import kr.co.ktscholarship.backend.auth.dto.RefreshTokenRequest;
import kr.co.ktscholarship.backend.auth.dto.TokenResponse;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfig.TAG_AUTH)
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "로그인", description = """
            이메일·비밀번호로 access token(기본 30분)과 refresh token(기본 14일)을 발급합니다.
            access token이 만료되면 refresh token으로 /v1/auth/refresh를 호출해 새 쌍을 받습니다.

            비밀번호를 5번 연속 틀리면 30분 동안 잠깁니다(423 ACCOUNT_LOCKED, error.details.lockedUntil에 풀리는 시각).
            잠긴 동안은 비밀번호가 맞아도 423이고, 시간이 지나면 저절로 풀립니다. 로그인에 성공하면 실패 횟수는 0으로 돌아갑니다.""")
    @SecurityRequirements // 공개 API: Swagger에서 토큰 없이 호출
    @PostMapping(value = "/v1/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request
    ) {
        return ApiResponse.success(authService.login(loginRequest), request.getRequestURI());
    }

    @Operation(
            summary = "토큰 재발급",
            description = """
                    refresh token으로 새 access·refresh token을 받습니다. 보낸 refresh token은 바로 폐기되므로(회전) 응답의 새 값으로 바꿔 저장해야 합니다.
                    이미 쓴 refresh token이 다시 오면 탈취로 보고 그 사용자의 refresh token을 모두 폐기합니다.
                    여러 탭에서 동시에 재발급하면 한쪽이 '이미 쓴 토큰'이 되므로, 재발급은 한 번에 하나만 보내 주세요.
                    Authorization 헤더는 보지 않습니다(만료된 access token이 붙어 와도 됨).""",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST - refreshToken이 없습니다."),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "INVALID_REFRESH_TOKEN - 없거나 만료·폐기된 토큰, 사용 중지·삭제된 계정, 비밀번호 변경 전에 받은 토큰. 다시 로그인해야 합니다.")
            }
    )
    @SecurityRequirements
    @PostMapping(value = "/v1/auth/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(authService.refresh(body.refreshToken()), request.getRequestURI());
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    이 기기의 refresh token을 폐기합니다. 다른 기기의 로그인은 유지됩니다.
                    없거나 이미 폐기된 토큰이어도 200입니다. 프론트는 갖고 있던 access·refresh token을 지우면 됩니다
                    (access token은 서버에 기록되지 않아 남은 수명 동안은 유효하지만, 지우면 쓸 곳이 없습니다).""",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 완료"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST - refreshToken이 없습니다.")
            }
    )
    @SecurityRequirements
    @PostMapping(value = "/v1/auth/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> logout(
            @Valid @RequestBody RefreshTokenRequest body,
            HttpServletRequest request
    ) {
        authService.logout(body.refreshToken());
        return ApiResponse.success(null, request.getRequestURI());
    }

    @Operation(summary = "내 정보 조회", description = "담당자의 accessiblePrograms는 전체 프로그램의 id·name 목록이며, 프로그램이 없으면 빈 배열입니다. 장학생은 null입니다. 장학생은 cohort{id,name}와 university(학교 이름)가 채워지고, 프로필이 없으면 둘 다 null입니다. 담당자는 cohort·university가 null입니다.")
    @GetMapping(value = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MeResponse> getMe(@CurrentUserId Long userId, HttpServletRequest request) {
        return ApiResponse.success(authService.getMe(userId), request.getRequestURI());
    }

    @Operation(
            summary = "비밀번호 변경",
            description = "로그인한 본인의 비밀번호를 바꿉니다. 성공하면 passwordChangeRequired가 false가 됩니다. "
                    + "이전에 발급된 access·refresh token은 모든 기기에서 바로 끊기고, 응답의 새 토큰 쌍으로 바꿔 써야 합니다.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "INVALID_REQUEST(8자 미만·64자 초과), PASSWORD_MISMATCH(현재 비밀번호 틀림), SAME_AS_CURRENT(기존과 동일)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다.")
            }
    )
    @PatchMapping(value = "/v1/auth/password", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PasswordChangeResponse> changePassword(
            @CurrentUserId Long userId,
            @Valid @RequestBody PasswordChangeRequest passwordChangeRequest,
            HttpServletRequest request
    ) {
        return ApiResponse.success(authService.changePassword(userId, passwordChangeRequest), request.getRequestURI());
    }
}
