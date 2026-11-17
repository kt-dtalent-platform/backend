package kr.co.ktscholarship.backend.report;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('MANAGER')")
public class ManagerDashboardController {
    private final ManagerDashboardService service;
    public ManagerDashboardController(ManagerDashboardService service) { this.service=service; }
    @Operation(summary="담당자 대시보드",description="공개된 진행 중 요청의 대상자와 검토 대기 수를 집계합니다. 재제출은 검토 대기에 포함됩니다.")
    @GetMapping("/v1/dashboard/manager")
    public ApiResponse<ManagerDashboardService.Dashboard> dashboard(
        @RequestParam(name="programId",required=false) @Positive Long program,
        @RequestParam(name="cohortId",required=false) @Positive Long cohort,HttpServletRequest request) {
        return ApiResponse.success(service.find(program,cohort),request.getRequestURI());
    }
}
