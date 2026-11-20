package kr.co.ktscholarship.backend.file;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('MANAGER','SCHOLAR')")
public class FileDownloadController {
    private final FileDownloadService service;
    public FileDownloadController(FileDownloadService service) { this.service=service; }
    private boolean manager(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_MANAGER")); }

    @Operation(summary="파일 다운로드",description="담당자 또는 본인 장학생 권한 확인 후 5분 유효 GET URL로 리다이렉트합니다. 감사 로그를 남깁니다.")
    @GetMapping("/v1/files/{fileId}/download")
    public ResponseEntity<Void> download(@CurrentUserId Long user,Authentication auth,@PathVariable("fileId") @Positive long id,HttpServletRequest request) {
        var link=service.link(user,manager(auth),id,null,true,request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.url())).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy","no-referrer").build();
    }

    @Operation(summary="첨부 원본 보기 URL",description="현재 프론트 검토 화면용 JSON 응답. 다운로드와 동일한 권한·감사 기록을 적용합니다.")
    @GetMapping("/v1/submissions/{submissionId}/files/{fileId}/url")
    public ApiResponse<FileDownloadService.Link> preview(@CurrentUserId Long user,Authentication auth,
        @PathVariable("submissionId") @Positive long submission,@PathVariable("fileId") @Positive long file,
        HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        return ApiResponse.success(service.link(user,manager(auth),file,submission,false,request.getRemoteAddr()),request.getRequestURI());
    }
}
