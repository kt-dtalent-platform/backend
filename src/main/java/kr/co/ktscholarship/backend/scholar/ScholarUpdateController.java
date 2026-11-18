package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScholarUpdateController {

    private final ScholarUpdateService service;

    public ScholarUpdateController(ScholarUpdateService service) {
        this.service = service;
    }

    @Operation(
        summary = "장학생 정보 수정",
        description = "이름·연락처·학교·전공을 부분 수정합니다. 담당자 전용입니다. 학교는 university와 선택한 schoolId를 보내며, 직접 입력은 schoolId를 null 또는 생략합니다. 학교를 보내지 않으면 기존 학교를 유지합니다."
    )
    @PatchMapping(value = "/v1/scholars/{scholarId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ScholarUpdateResponse> updateScholar(
        @PathVariable("scholarId") @Positive long scholarId,
        @Valid @RequestBody ScholarUpdateRequest body, HttpServletRequest request
    ) {
        return ApiResponse.success(service.update(scholarId, body), request.getRequestURI());
    }
}
