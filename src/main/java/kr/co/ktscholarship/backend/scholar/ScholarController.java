package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScholarController {

    private final ScholarListService service;

    public ScholarController(ScholarListService service) {
        this.service = service;
    }

    @Operation(summary = "장학생 목록 조회", description = "담당자는 전체 장학생을 조회합니다. 제출 상태 필터는 requestId와 submitStatus를 함께 지정합니다.")
    @GetMapping(value = "/v1/scholars", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ScholarListResponse> getScholars(
        @RequestParam(name = "cohortId", required = false) @Positive Long cohortId,
        @RequestParam(name = "status", required = false)
        @Pattern(regexp = "ENROLLED|LEAVE|GRADUATED|WITHDRAWN") String status,
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "requestId", required = false) @Positive Long requestId,
        @RequestParam(name = "submitStatus", required = false)
        @Pattern(regexp = "NOT_SUBMITTED|DRAFT|SUBMITTED|RESUBMITTED|REVISION_REQUIRED|CONFIRMED") String submitStatus,
        @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
        @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            service.findAll(cohortId, status, keyword, requestId, submitStatus, page, pageSize), request.getRequestURI()
        );
    }
}
