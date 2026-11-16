package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class ScholarStatusController {
    private final ScholarStatusService service;
    public ScholarStatusController(ScholarStatusService service) { this.service = service; }

    @Operation(summary = "장학생 상태 변경", description = "담당자 전용. 수료 시 completedYear, 중단 시 withdrawalReason, OTHER이면 상세 사유가 필요합니다. 휴학도 지원합니다.")
    @PatchMapping("/v1/scholars/{scholarId}/status")
    public ApiResponse<ScholarStatusService.Changed> change(
        @PathVariable("scholarId") @Positive long id, @Valid @RequestBody ScholarStatusRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.change(id, body), request.getRequestURI());
    }

    @Operation(summary = "장학생 상태 일괄 변경", description = "담당자 전용. 최대 1000명, 중복 ID는 한 번만 처리합니다. 없는 장학생이 있으면 전체 취소합니다. updated는 실제 변경 인원입니다.")
    @PatchMapping("/v1/scholars/status")
    public ApiResponse<ScholarStatusService.BulkChanged> changeAll(
        @Valid @RequestBody ScholarBulkStatusRequest body, HttpServletRequest request
    ) {
        return ApiResponse.success(service.changeAll(body), request.getRequestURI());
    }
}
