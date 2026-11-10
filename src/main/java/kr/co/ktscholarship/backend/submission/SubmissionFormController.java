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

    private final SubmissionFormRepository repository;

    public SubmissionFormController(SubmissionFormRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "내 제출 폼 기본 조회", description = "토큰의 사용자 ID로 본인 폼을 조회합니다. 기존 중첩 조회 모델을 반환하며 최종 응답 계약·개인정보 안내문·보완 기한은 별도 확인 중입니다.")
    @GetMapping("/v1/me/submissions/{requestId}")
    public ApiResponse<SubmissionFormData> getForm(
        @PathVariable("requestId") @Positive long requestId,
        @CurrentUserId Long userId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(repository.find(requestId, userId), request.getRequestURI());
    }
}
