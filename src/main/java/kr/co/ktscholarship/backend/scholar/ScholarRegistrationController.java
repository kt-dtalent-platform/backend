package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScholarRegistrationController {
    private final ScholarRegistrationService service;

    public ScholarRegistrationController(ScholarRegistrationService service) {
        this.service = service;
    }

    @Operation(summary = "장학생 개별 등록", description = "담당자가 모든 기수에 등록할 수 있습니다. 학교는 필수이며 생년월일은 받지 않습니다. 임시 비밀번호는 응답에서 한 번 제공하므로 담당자가 별도로 안내합니다. 첫 로그인 후 비밀번호를 변경해야 합니다.",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "담당자 권한 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL")
        })
    @PostMapping("/v1/scholars")
    public ResponseEntity<ApiResponse<ScholarCreateResponse>> register(
        @Valid @RequestBody ScholarCreateRequest body, HttpServletRequest request
    ) {
        var result = service.register(body);
        return ResponseEntity.created(URI.create("/v1/scholars/" + result.id()))
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(result, request.getRequestURI()));
    }
}
