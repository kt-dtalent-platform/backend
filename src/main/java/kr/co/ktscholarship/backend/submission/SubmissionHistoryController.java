package kr.co.ktscholarship.backend.submission;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubmissionHistoryController {
    private final SubmissionHistoryService service;
    public SubmissionHistoryController(SubmissionHistoryService service) { this.service=service; }
    @Operation(summary="내 제출 이력",description="토큰 본인의 작성 중이 아닌 제출을 조회합니다. 보관기간 파기 기능은 현재 없어 purged는 false입니다.")
    @GetMapping("/v1/me/submissions")
    public ApiResponse<SubmissionHistoryService.HistoryPage> history(@CurrentUserId Long user,
        @RequestParam(name="status",required=false) String status,@RequestParam(name="page",defaultValue="1") @Min(1) int page,
        @RequestParam(name="page_size",defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest request) {
        return ApiResponse.success(service.find(user,status,page,size),request.getRequestURI());
    }
}
