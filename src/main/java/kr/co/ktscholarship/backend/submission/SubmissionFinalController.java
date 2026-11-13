package kr.co.ktscholarship.backend.submission;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubmissionFinalController {
    private final SubmissionFinalService service;
    public SubmissionFinalController(SubmissionFinalService service) { this.service=service; }

    @Operation(summary="최종 제출",description="전체 입력과 UPLOADED 필수 파일을 검증하고 상태·버전·변경 전 스냅샷을 함께 저장합니다.")
    @PostMapping("/v1/me/submissions/{requestId}/submit")
    public ApiResponse<SubmissionFinalService.Result> submit(@CurrentUserId Long user,@PathVariable("requestId") @Positive long id,
        @Valid @RequestBody SubmissionDraftRequest body,HttpServletRequest request) {
        return ApiResponse.success(service.submit(user,id,body,false),request.getRequestURI());
    }

    @Operation(summary="보완 재제출",description="최신 미해결 보완 항목의 답변만 병합하며 지정 외 항목은 거절합니다. MVP 마감은 요청 공통 기한을 사용합니다.")
    @PostMapping("/v1/me/submissions/{requestId}/resubmit")
    public ApiResponse<SubmissionFinalService.Result> resubmit(@CurrentUserId Long user,@PathVariable("requestId") @Positive long id,
        @Valid @RequestBody SubmissionDraftRequest body,HttpServletRequest request) {
        return ApiResponse.success(service.submit(user,id,body,true),request.getRequestURI());
    }
}
