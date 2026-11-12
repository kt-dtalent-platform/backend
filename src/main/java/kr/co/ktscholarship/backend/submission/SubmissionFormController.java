package kr.co.ktscholarship.backend.submission;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubmissionFormController {

    private final SubmissionFormService service;

    public SubmissionFormController(SubmissionFormService service) {
        this.service = service;
    }

    @Operation(
        summary = "내 제출 폼 조회",
        description = """
            토큰의 사용자 ID로 본인 제출 폼을 조회합니다.
            최초 진입은 submission=null이며 프론트는 미제출·version 0으로 처리합니다.
            수정 가능 항목은 프론트에서 요청 상태·제출 상태·revision.fieldKeys로 계산합니다.
            fields는 sortOrder 순이며 options는 value·label 객체 배열, helpText는 없으면 null입니다.
            request.requestDueAt은 UTC(Z)이며 별도 보완 기한·개인정보 안내문은 MVP에서 제공하지 않습니다.
            internalMemo와 비공개 S3 저장 키는 포함하지 않습니다.
            """,
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "본인 제출 폼 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST - 올바르지 않은 요청 ID", content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요", content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED / NOT_A_TARGET - 역할 또는 대상자 권한 없음", content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REQUEST_NOT_FOUND - 없거나 공개되지 않은 요청", content = @io.swagger.v3.oas.annotations.media.Content)
        }
    )
    @GetMapping("/v1/me/submissions/{requestId}")
    public ApiResponse<SubmissionFormData> getForm(
        @PathVariable("requestId") @Positive long requestId,
        @CurrentUserId Long userId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.find(requestId, userId), request.getRequestURI());
    }
}
