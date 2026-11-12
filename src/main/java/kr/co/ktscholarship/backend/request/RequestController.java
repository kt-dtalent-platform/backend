package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RequestController {

    private final RequestListRepository repository;

    public RequestController(RequestListRepository repository) {
        this.repository = repository;
    }

    @Operation(
        summary = "요청 목록 조회",
        description = """
            요청 목록과 요청별 제출 현황을 조회합니다. 담당자 전용 API이며, MVP에서는 전체 요청을 조회합니다.
            정렬: 진행 중 → 작성 중 → 마감, 같은 상태 안에서는 최신 생성순.
            submittedCount는 작성 중(DRAFT)이 아닌 제출 수(보완 필요 포함)이고, 미제출은 targetCount - submittedCount입니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - status, page, page_size 값이 올바르지 않습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다.")
        }
    )
    @GetMapping(value = "/v1/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RequestListResponse> getRequests(
        @Parameter(description = "프로그램 필터")
        @RequestParam(name = "programId", required = false) @Positive Long programId,
        @Parameter(description = "기수 필터. 프로그램 전체 대상 요청은 기수 필터에 걸리지 않습니다.")
        @RequestParam(name = "cohortId", required = false) @Positive Long cohortId,
        @Parameter(description = "요청 상태. 없으면 전체")
        @RequestParam(name = "status", required = false) RequestStatus status,
        @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
        @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            repository.findAll(programId, cohortId, status, page, pageSize), request.getRequestURI()
        );
    }
}
