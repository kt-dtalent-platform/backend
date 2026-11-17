package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class TargetDeadlineController {
    private final TargetDeadlineService service;
    public TargetDeadlineController(TargetDeadlineService service) { this.service=service; }

    @Operation(summary="장학생별 유효 마감일 조회",description="담당자 전용. 공통 마감일과 개별 연장 반영 마감일을 UTC로 반환합니다.")
    @GetMapping("/v1/requests/{requestId}/targets/{scholarId}/deadline")
    public ApiResponse<TargetDeadlineService.Deadline> find(@PathVariable("requestId") @Positive long requestId,
        @PathVariable("scholarId") @Positive long scholar,HttpServletRequest request) {
        return ApiResponse.success(service.find(requestId,scholar),request.getRequestURI());
    }

    @Operation(summary="장학생별 마감일 연장",description="담당자 전용. 공개된 진행 중 요청의 대상자만 연장합니다. 현재 유효 마감일보다 뒤인 미래 시각이어야 합니다. 다른 학생과 공통 마감일은 바꾸지 않습니다. 명시적으로 닫힌 요청은 연장하지 않습니다.")
    @PatchMapping("/v1/requests/{requestId}/targets/{scholarId}/deadline")
    public ApiResponse<TargetDeadlineService.Deadline> extend(@CurrentUserId Long actor,
        @PathVariable("requestId") @Positive long requestId,@PathVariable("scholarId") @Positive long scholar,
        @Valid @RequestBody Extension body,HttpServletRequest request) {
        return ApiResponse.success(service.extend(requestId,scholar,body.dueAt(),actor),request.getRequestURI());
    }
    public record Extension(@NotNull @Future Instant dueAt) {}
}
