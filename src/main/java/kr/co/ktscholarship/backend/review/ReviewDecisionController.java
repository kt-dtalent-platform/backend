package kr.co.ktscholarship.backend.review;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// /v1/submissions/**는 보안 설정에서 로그인만 확인하므로 담당자 검사를 여기서 한다
@Tag(name = OpenApiConfig.TAG_REVIEW)
@RestController
@PreAuthorize("hasRole('MANAGER')")
public class ReviewDecisionController {

    private final ReviewDecisionService service;

    public ReviewDecisionController(ReviewDecisionService service) {
        this.service = service;
    }

    @Operation(
        summary = "확인 완료",
        description = """
            제출(SUBMITTED)·재제출(RESUBMITTED)된 제출을 확인 완료(CONFIRMED)로 바꿉니다. 승인·결재가 아니라 검토를 마쳤다는 표시입니다.
            확인 완료 뒤에는 장학생이 고칠 수 없고, 보완 요청도 받지 않습니다. 담당자 전용 API입니다.
            version은 제출 상세에서 받은 값을 보냅니다. 그사이 장학생이 다시 냈으면 409입니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "INVALID_REQUEST - version이 없거나 올바르지 않습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "SUBMISSION_NOT_FOUND - 제출이 없거나 아직 작성 중입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "VERSION_CONFLICT - 그사이 바뀌었습니다. 상세를 다시 불러와야 합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422", description = "SUBMISSION_NOT_REVIEWABLE - 이미 확인 완료됐거나 보완 중입니다.")
        }
    )
    @PostMapping(value = "/v1/submissions/{submissionId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ReviewDecisionResponse> confirm(
        @PathVariable("submissionId") long submissionId,
        @CurrentUserId Long managerId,
        @Valid @RequestBody ReviewConfirmRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.confirm(submissionId, managerId, body), request.getRequestURI());
    }

    @Operation(
        summary = "항목 지정 보완 요청",
        description = """
            제출 전체가 아니라 고칠 항목(fieldKeys)만 골라 보완을 요청합니다. 상태는 보완 필요(REVISION_REQUIRED)가 되고,
            장학생은 보완 재제출 API로 이 항목만 고쳐 다시 냅니다. 담당자 전용 API입니다.
            dueAt(보완 기한)은 그 장학생의 재제출 마감이 됩니다. 장학생의 지금 마감(연장 마감이 있으면 그것)보다 늦을 때만 마감을 바꾸고, 이르면 지금 마감을 그대로 둡니다.
            마감 전에는 dueAt을 비워 둘 수 있습니다(남은 기간에 재제출). 마감이 지났으면 dueAt이 꼭 있어야 하고, 지난 날짜도 400입니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보완 요청 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = """
                    INVALID_REQUEST - 항목이 비었거나 중복·이 요청에 없는 항목, 사유 없음, 지난 기한, 마감이 지났는데 기한 없음입니다.
                    details에 필드 이름(fieldKeys, reason, dueAt, version)이 담깁니다."""),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "SUBMISSION_NOT_FOUND - 제출이 없거나 아직 작성 중입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "VERSION_CONFLICT - 그사이 바뀌었습니다. 상세를 다시 불러와야 합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422", description = "SUBMISSION_NOT_REVIEWABLE - 이미 확인 완료됐거나 보완 중입니다.")
        }
    )
    @PostMapping(value = "/v1/submissions/{submissionId}/revision", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ReviewDecisionResponse> requestRevision(
        @PathVariable("submissionId") long submissionId,
        @CurrentUserId Long managerId,
        @Valid @RequestBody ReviewRevisionRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.requestRevision(submissionId, managerId, body), request.getRequestURI());
    }
}
