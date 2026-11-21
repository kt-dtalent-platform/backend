package kr.co.ktscholarship.backend.cohort;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfig.TAG_REFERENCE)
@RestController
public class CohortController {

    private final CohortService service;

    public CohortController(CohortService service) {
        this.service = service;
    }

    @Operation(summary = "기수 생성", description = "담당자 전용. 이름 필수, 시작일·종료일 선택. 같은 프로그램의 같은 이름은 409입니다.", responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PROGRAM_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_COHORT")
    })
    @PostMapping("/v1/programs/{programId}/cohorts")
    public ResponseEntity<ApiResponse<CohortItem>> create(
        @PathVariable("programId") long programId,
        @Valid @RequestBody CohortCreateRequest body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(201)
            .body(ApiResponse.success(service.create(programId, body), request.getRequestURI()));
    }

    @Operation(summary = "기수 목록 조회", responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 정보가 없거나 만료되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "ACCESS_DENIED - 담당자 권한이 필요합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "PROGRAM_NOT_FOUND - 프로그램을 찾을 수 없습니다.")
    })
    @GetMapping(value = "/v1/programs/{programId}/cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<CohortListResponse> getCohorts(
            @PathVariable("programId") long programId, HttpServletRequest request
    ) {
        return ApiResponse.success(
                new CohortListResponse(service.findByProgramId(programId)), request.getRequestURI()
        );
    }
}
