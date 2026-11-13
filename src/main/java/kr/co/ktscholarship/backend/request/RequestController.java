package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RequestController {

    private final RequestListRepository listRepository;
    private final RequestService requestService;

    public RequestController(RequestListRepository listRepository, RequestService requestService) {
        this.listRepository = listRepository;
        this.requestService = requestService;
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
            listRepository.findAll(programId, cohortId, status, page, pageSize), request.getRequestURI()
        );
    }

    @Operation(
        summary = "요청 상세 조회",
        description = """
            작성 중인 요청 고치기 화면에 채울 요청 정보를 조회합니다. 담당자 전용 API입니다.
            공개된 요청도 조회됩니다(status로 구분). 수정 가능 여부는 수정 API에서 검사합니다.
            제출 집계는 제출 현황 API에서 제공합니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - requestId가 올바르지 않습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "REQUEST_NOT_FOUND - 요청을 찾을 수 없습니다.")
        }
    )
    @GetMapping(value = "/v1/requests/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RequestDetailResponse> getRequest(
        @PathVariable("requestId") @Positive long requestId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(requestService.getDetail(requestId), request.getRequestURI());
    }

    @Operation(
        summary = "요청 만들기",
        description = """
            요청을 작성 중(DRAFT)으로 저장합니다. 담당자 전용 API이며, 장학생에게는 공개 전까지 보이지 않습니다.
            cohortId가 null이면 프로그램 전체 대상입니다. 입력 항목은 공개할 때 템플릿에서 복사합니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = """
                    INVALID_REQUEST - 본문 형식이 틀렸거나, 없는 프로그램·그 프로그램 소속이 아닌 기수·
                    없거나 사용하지 않는 템플릿·지난 마감 시각입니다. details에 필드 이름이 담깁니다."""),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다.")
        }
    )
    @PostMapping(value = "/v1/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RequestSaveResponse> createRequest(
        @CurrentUserId Long managerId,
        @Valid @RequestBody RequestSaveRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(requestService.create(managerId, body), request.getRequestURI());
    }

    @Operation(
        summary = "작성 중인 요청 고치기",
        description = """
            작성 중(DRAFT)인 요청을 고칩니다. 담당자 전용 API입니다.
            본문은 요청 만들기와 같고, 폼 전체를 보냅니다(보낸 값으로 모두 바뀝니다).""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - 요청 만들기와 같은 검사입니다. details에 필드 이름이 담깁니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "REQUEST_NOT_FOUND - 요청을 찾을 수 없습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "REQUEST_NOT_EDITABLE - 이미 공개되었거나 마감된 요청입니다.")
        }
    )
    @PatchMapping(value = "/v1/requests/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RequestSaveResponse> updateRequest(
        @PathVariable("requestId") @Positive long requestId,
        @Valid @RequestBody RequestSaveRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(requestService.update(requestId, body), request.getRequestURI());
    }
}
