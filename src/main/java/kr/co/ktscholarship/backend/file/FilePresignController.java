package kr.co.ktscholarship.backend.file;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FilePresignController {
    private final FilePresignService service;

    public FilePresignController(FilePresignService service) {
        this.service = service;
    }

    @Operation(summary = "업로드 URL 발급", description = """
        장학생 본인 제출의 FILE 항목에 10분 유효한 S3 PUT URL을 발급하고 PENDING 파일을 생성합니다.
        같은 Content-Type 헤더로 업로드하고 완료 API에서 실제 객체를 재검증해야 합니다.
        400 입력·확장자·개수 오류, 401 미인증, 403 타인 제출·담당자,
        404 제출 없음, 413 용량 초과, 422 마감·수정 불가, 503 저장소 미설정·인증 오류.
        """)
    @PostMapping(value = "/v1/submissions/{submissionId}/files/presign", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<FilePresignResponse> presign(
        @CurrentUserId Long userId, @PathVariable("submissionId") @Positive long submissionId,
        @Valid @RequestBody FilePresignRequest body, HttpServletRequest request, HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.presign(userId, submissionId, body), request.getRequestURI());
    }
}
