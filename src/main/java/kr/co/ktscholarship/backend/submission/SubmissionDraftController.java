package kr.co.ktscholarship.backend.submission;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubmissionDraftController {
    private final SubmissionDraftService service;

    public SubmissionDraftController(SubmissionDraftService service) {
        this.service = service;
    }

    @Operation(summary = "제출 임시저장", description = """
        장학생 전용. 최초 version은 0입니다. 필수값 누락은 허용하며 입력한 값의 형식만 검사합니다.
        일반 초안의 answers는 전체 교체합니다. 보완 중에는 지정 항목만 수정하고 보완 상태를 유지합니다.
        입력 형식 오류 400, 미인증 401, 권한·대상자 오류 403, 요청 없음 404,
        버전 충돌 409, 마감·제출 완료·보완 대상 외 수정 422를 반환합니다.
        """)
    @PutMapping(value = "/v1/me/submissions/{requestId}/draft", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SubmissionDraftResponse> save(
        @CurrentUserId Long userId, @PathVariable("requestId") @Positive long requestId,
        @Valid @RequestBody SubmissionDraftRequest body, HttpServletRequest request
    ) {
        return ApiResponse.success(service.save(userId, requestId, body), request.getRequestURI());
    }
}
