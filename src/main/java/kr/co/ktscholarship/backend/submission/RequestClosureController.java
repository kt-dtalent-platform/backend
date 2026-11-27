package kr.co.ktscholarship.backend.submission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import org.springframework.web.bind.annotation.*;

@Tag(name=OpenApiConfig.TAG_REQUEST)
@RestController
public class RequestClosureController {
    private final RequestDeadlineClosureService service;
    public RequestClosureController(RequestDeadlineClosureService service) { this.service=service; }

    @Operation(summary="요청 수동 마감",description="담당자 전용. 미제출·검토 대기가 남아도 공개 요청을 마감합니다. 이미 마감된 요청은 그대로 반환합니다.")
    @PostMapping("/v1/requests/{requestId}/close")
    public ApiResponse<RequestDeadlineClosureService.Closure> close(@PathVariable @Positive long requestId,
        @CurrentUserId Long actor,HttpServletRequest request) {
        return ApiResponse.success(service.close(requestId,actor),request.getRequestURI());
    }
}
