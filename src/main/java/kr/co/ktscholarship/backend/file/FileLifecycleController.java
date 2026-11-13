package kr.co.ktscholarship.backend.file;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('SCHOLAR')")
public class FileLifecycleController {
    private final FileLifecycleService service;
    public FileLifecycleController(FileLifecycleService service) { this.service=service; }

    @Operation(summary="업로드 완료 처리",description="본인 파일의 실제 크기·타입을 HeadObject로 검증합니다. 불일치 파일은 삭제합니다.")
    @PostMapping("/v1/submissions/{submissionId}/files/{fileId}/complete")
    public ApiResponse<FileLifecycleService.Completed> complete(@CurrentUserId Long user,
        @PathVariable("submissionId") @Positive long submissionId,@PathVariable("fileId") @Positive long fileId,HttpServletRequest request) {
        return ApiResponse.success(service.complete(user,submissionId,fileId),request.getRequestURI());
    }

    @Operation(summary="파일 삭제",description="본인의 작성 중·보완 지정 항목 파일만 삭제합니다. DB는 DELETED, 실제 객체는 즉시 삭제합니다.")
    @DeleteMapping("/v1/files/{fileId}")
    public ApiResponse<Void> delete(@CurrentUserId Long user,@PathVariable("fileId") @Positive long id,HttpServletRequest request) {
        service.delete(user,id);
        return ApiResponse.success(null,request.getRequestURI());
    }
}
