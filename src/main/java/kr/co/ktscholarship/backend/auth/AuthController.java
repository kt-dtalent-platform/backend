package kr.co.ktscholarship.backend.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.co.ktscholarship.backend.auth.dto.LoginRequest;
import kr.co.ktscholarship.backend.auth.dto.LoginResponse;
import kr.co.ktscholarship.backend.auth.dto.MeResponse;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "로그인", description = "이메일·비밀번호로 access token을 발급합니다. refresh token은 후속 이슈에서 추가합니다.")
    @SecurityRequirements // 공개 API: Swagger에서 토큰 없이 호출
    @PostMapping(value = "/v1/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request
    ) {
        return ApiResponse.success(authService.login(loginRequest), request.getRequestURI());
    }

    @Operation(summary = "내 정보 조회", description = "담당자의 accessiblePrograms는 전체 프로그램의 id·name 목록이며, 프로그램이 없으면 빈 배열입니다. 장학생은 null입니다. cohort는 현재 null입니다.")
    @GetMapping(value = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MeResponse> getMe(@CurrentUserId Long userId, HttpServletRequest request) {
        return ApiResponse.success(authService.getMe(userId), request.getRequestURI());
    }
}
