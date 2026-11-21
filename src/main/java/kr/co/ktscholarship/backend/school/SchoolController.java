package kr.co.ktscholarship.backend.school;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = OpenApiConfig.TAG_REFERENCE)
@RestController
@PreAuthorize("hasRole('MANAGER')")
public class SchoolController {
    private final SchoolRepository repository;
    public SchoolController(SchoolRepository repository) { this.repository = repository; }

    @Operation(summary = "학교 목록 검색", description = "담당자 전용. 캠퍼스가 포함된 학교명으로 검색합니다. 공백·괄호를 무시하며 빈 검색은 빈 목록입니다. 등록·수정 시 목록 밖 학교명 직접 입력도 허용합니다.")
    @GetMapping("/v1/schools")
    public ApiResponse<SchoolSearchResponse> search(
        @RequestParam(name = "keyword", defaultValue = "") @Size(max = 100) String keyword,
        @RequestParam(name = "size", defaultValue = "8") @Min(1) @Max(50) int size,
        HttpServletRequest request
    ) {
        return ApiResponse.success(new SchoolSearchResponse(repository.search(keyword, size)), request.getRequestURI());
    }
}
