package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfig.TAG_SCHOLAR)
@RestController
public class ScholarImportController {
    private final ScholarImportService service;

    public ScholarImportController(ScholarImportService service) {
        this.service = service;
    }

    @Operation(summary = "장학생 명단 일괄 등록", description = """
        담당자 전용. 프론트에서 엑셀을 읽고 {cohortId, scholars:[...]} JSON을 보냅니다.
        1~500명. 이름·이메일·학교 필수, 학교ID 선택. 한 명이라도 실패하면 전체 취소합니다.
        성공 201: {created, items:[{row,id,email,temporaryPassword}]}. 프론트는 created를 사용합니다.
        409 DUPLICATE_EMAIL의 details는 중복된 이메일 문자열 목록입니다.
        임시 비밀번호는 성공 응답에서만 제공하며 첫 로그인 시 변경해야 합니다.
        입력 오류 400, 없는 기수 404. row는 명단 순서 + 1(헤더 포함)입니다.""")
    @PostMapping(value = "/v1/scholars/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ScholarImportResponse>> bulk(
        @Valid @RequestBody ScholarBulkCreateRequest body, HttpServletRequest request
    ) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(service.register(body), request.getRequestURI()));
    }

}
