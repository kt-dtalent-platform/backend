package kr.co.ktscholarship.backend.report;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('MANAGER')")
public class SubmissionExportController {
    private final SubmissionExportService service;
    public SubmissionExportController(SubmissionExportService service) { this.service=service; }
    @Operation(summary="요청 제출 현황 다운로드",description="대상자 전체(미제출 포함)를 XLSX 또는 CSV로 내려받습니다. status와 columns는 쉼표로 구분합니다. 연락처는 마스킹합니다.")
    @GetMapping("/v1/requests/{requestId}/export")
    public ResponseEntity<byte[]> export(@CurrentUserId Long user,@PathVariable("requestId") @Positive long request,
        @RequestParam(name="format",defaultValue="xlsx") String format,@RequestParam(name="status",required=false) String status,
        @RequestParam(name="columns",required=false) String columns,HttpServletRequest servlet) {
        var result=service.create(user,request,format,status,columns,servlet.getRemoteAddr());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType())).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.filename(),StandardCharsets.UTF_8).build().toString())
            .body(result.bytes());
    }
}
