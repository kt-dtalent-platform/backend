package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfig.TAG_REVIEW)
@RestController
@PreAuthorize("hasRole('MANAGER')")
public class SubmissionReviewController {

    private final SubmissionReviewService service;

    public SubmissionReviewController(SubmissionReviewService service) {
        this.service = service;
    }

    @Operation(
        summary = "요청별 제출 현황",
        description = """
            요청의 대상 장학생 전원과 제출 상태를 조회합니다. 담당자 전용 API입니다.
            제출 행이 없거나 작성 중(DRAFT)이면 NOT_SUBMITTED로 나오고 submissionId는 null입니다.
            기본 순서: 검토 대기(오래 기다린 순) → 보완 필요 → 미제출 → 확인 완료, 같은 칸 안에서는 이름순.
            summary는 상태·검색 조건과 상관없이 대상자 전체 기준입니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - status, keyword, page, page_size 값이 올바르지 않습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "REQUEST_NOT_FOUND - 요청을 찾을 수 없습니다.")
        }
    )
    @GetMapping(value = "/v1/requests/{requestId}/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SubmissionStatusResponse> getSubmissionStatus(
        @PathVariable("requestId") @Positive long requestId,
        @Parameter(description = "상태 칩. WAITING은 제출 + 재제출. 없으면 전체")
        @RequestParam(name = "status", required = false)
        @Pattern(regexp = "NOT_SUBMITTED|WAITING|REVISION_REQUIRED|CONFIRMED") String status,
        @Parameter(description = "이름 검색")
        @RequestParam(name = "keyword", required = false) @Size(max = 50) String keyword,
        @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
        @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.getStatus(requestId, status, keyword, page, pageSize), request.getRequestURI());
    }

    @Operation(
        summary = "제출 상세 (개별 검토)",
        description = """
            제출된 입력값·첨부파일·상태 이력·보완 요청 내역을 조회합니다. 담당자 전용 API입니다.
            작성 중(DRAFT)인 제출은 아직 내지 않은 것이라 404입니다.
            파일은 업로드가 끝난 것만 메타데이터로 주고, 원본은 원본 보기 주소 API로 엽니다.
            analysis(문서 분석 결과)는 연결 전까지 null입니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - submissionId가 올바르지 않습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "SUBMISSION_NOT_FOUND - 제출이 없거나 아직 작성 중입니다.")
        }
    )
    @GetMapping(value = "/v1/submissions/{submissionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SubmissionDetailResponse> getSubmissionDetail(
        @PathVariable("submissionId") @Positive long submissionId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.getDetail(submissionId), request.getRequestURI());
    }
}
