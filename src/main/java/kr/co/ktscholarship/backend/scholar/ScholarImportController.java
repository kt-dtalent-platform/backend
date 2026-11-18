package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ScholarImportController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ScholarImportService service;
    private final ScholarImportExcel excel;

    public ScholarImportController(ScholarImportService service, ScholarImportExcel excel) {
        this.service = service;
        this.excel = excel;
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

    @Operation(summary = "장학생 엑셀 등록 양식", description = "담당자 전용. 이름·이메일·학교 필수, 연락처·전공·학번·학교ID 선택. 헤더 순서를 유지하고 2~201행에 입력합니다. 학번·연락처는 문자 형식으로 입력합니다.")
    @GetMapping(value = "/v1/scholars/import/template", produces = XLSX)
    public ResponseEntity<byte[]> template() throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=scholars-import-template.xlsx")
            .body(excel.template());
    }

    @Operation(summary = "장학생 엑셀 일괄 등록", description = """
        담당자 전용. cohortId와 file을 multipart/form-data로 보냅니다.
        시트 하나인 .xlsx, 최대 1MB·200명. 수식은 받지 않습니다.
        오류가 있으면 전체 등록을 취소하고 details에 row(엑셀 행 번호), field, message를 반환합니다.
        400 입력·파일 오류, 404 없는 기수, 409 이메일 중복, 413 업로드 크기 초과. 성공은 201입니다.
        임시 비밀번호는 성공 응답에서 한 번 제공하며 첫 로그인 후 변경해야 합니다.""",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "전체 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "파일 또는 행 입력 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "담당자 권한 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "파일 크기 초과")
        })
    @PostMapping(value = "/v1/scholars/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ScholarImportResponse>> register(
        @RequestParam("cohortId") @Positive long cohortId,
        @RequestParam("file") MultipartFile file, HttpServletRequest request
    ) {
        var result = service.register(cohortId, file);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(result, request.getRequestURI()));
    }
}
