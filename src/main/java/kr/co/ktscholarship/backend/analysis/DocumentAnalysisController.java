package kr.co.ktscholarship.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// /v1/submissions/**, /v1/analysis-results/**는 로그인만 확인한다. 담당자인지·본인인지는 서비스에서 본다
@RestController
public class DocumentAnalysisController {

    private final DocumentAnalysisService service;

    public DocumentAnalysisController(DocumentAnalysisService service) {
        this.service = service;
    }

    @Operation(
        summary = "문서 분석 요청",
        description = """
            업로드한 성적증명서를 OCR로 읽어 장학생 입력값(gpa 학기 평점, gpa_scale 만점, credits 취득 학점)과 비교하고 결과를 저장합니다.
            담당자와 본인 장학생이 호출할 수 있습니다. 처리에 몇 초 걸리며 응답에 결과가 담깁니다.
            분석만으로 제출 상태는 바뀌지 않습니다. 같은 제출을 다시 분석하면 회차가 오르고 이전 결과는 무효가 됩니다.
            OCR을 쓸 수 없거나 파일을 못 읽으면 status FAILED, overallResult UNREADABLE로 저장하고 failureReason에 이유를 남깁니다.
            비교 결과는 검토 보조용이며 최종 판단은 담당자가 합니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "분석 완료(실패 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 다른 장학생의 제출입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = """
                    SUBMISSION_NOT_FOUND - 제출이 없거나 담당자에게는 아직 작성 중입니다.
                    FILE_NOT_FOUND - 이 제출의 파일이 아니거나 업로드가 끝나지 않았거나 삭제된 파일입니다."""),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "413", description = "FILE_TOO_LARGE - 분석할 수 있는 크기(20MB)를 넘었습니다.")
        }
    )
    @PostMapping(value = "/v1/submissions/{submissionId}/files/{fileId}/analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DocumentAnalysisResponse> analyze(
        @PathVariable("submissionId") long submissionId,
        @PathVariable("fileId") long fileId,
        @CurrentUserId Long userId,
        Authentication authentication,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.analyze(userId, manager(authentication), submissionId, fileId),
            request.getRequestURI());
    }

    @Operation(
        summary = "문서 분석 결과 조회",
        description = """
            분석 당시의 입력값 비교 결과를 조회합니다. 담당자는 전체, 장학생은 본인 제출의 결과만 볼 수 있습니다.
            outdated가 true면 분석 뒤 입력값이 바뀐 것이라 다시 분석해야 합니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "ACCESS_DENIED - 다른 장학생의 결과입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "ANALYSIS_NOT_FOUND - 분석 결과를 찾을 수 없습니다.")
        }
    )
    @GetMapping(value = "/v1/analysis-results/{analysisId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DocumentAnalysisResponse> find(
        @PathVariable("analysisId") long analysisId,
        @CurrentUserId Long userId,
        Authentication authentication,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.find(userId, manager(authentication), analysisId), request.getRequestURI());
    }

    private static boolean manager(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER"));
    }
}
