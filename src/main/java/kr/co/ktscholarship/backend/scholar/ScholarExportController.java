package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Tag(name = OpenApiConfig.TAG_SCHOLAR)
@RestController
public class ScholarExportController {
    private final ScholarExportService service;
    public ScholarExportController(ScholarExportService service) { this.service = service; }

    @Operation(summary = "장학생 명단 내려받기", description = "담당자 전용 XLSX. status와 columns는 쉼표로 구분합니다. 이름은 필수 열, 연락처는 마스킹합니다.")
    @GetMapping("/v1/scholars/export")
    public ResponseEntity<byte[]> export(@CurrentUserId Long actor,
        @RequestParam(name = "format", defaultValue = "xlsx") String format,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "cohortId", required = false) @Positive Long cohort,
        @RequestParam(name = "columns", required = false) String columns, HttpServletRequest request
    ) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("scholars.xlsx").build().toString())
            .body(service.export(actor, format, status, cohort, columns, request.getRemoteAddr()));
    }
}
