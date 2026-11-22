package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = OpenApiConfig.TAG_AUTH)
@RestController
@PreAuthorize("hasRole('SCHOLAR')")
public class ScholarBankController {
    private final ScholarBankService service;
    public ScholarBankController(ScholarBankService service) { this.service = service; }

    @Operation(summary = "본인 계좌정보 조회", description = "장학생 본인 은행·예금주·마스킹된 계좌번호만 반환합니다. 미등록이면 null. 전체 번호는 담당자 장학생 상세에서만 조회합니다.")
    @GetMapping("/v1/me/bank-account")
    public ApiResponse<ScholarBankService.MaskedAccount> find(@CurrentUserId Long user,
        HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.find(user), request.getRequestURI());
    }

    @Operation(summary = "본인 계좌정보 등록·수정", description = "은행명·계좌번호·예금주를 함께 입력합니다. 계좌번호의 공백·하이픈은 제거하고 앞자리 0은 보존합니다. 응답에는 번호를 포함하지 않습니다.")
    @PatchMapping("/v1/me/bank-account")
    public ApiResponse<ScholarBankService.Updated> update(@CurrentUserId Long user,
        @Valid @RequestBody ScholarBankAccount body, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.update(user, body), request.getRequestURI());
    }
}
