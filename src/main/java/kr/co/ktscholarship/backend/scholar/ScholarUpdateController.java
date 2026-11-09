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

    private final ScholarUpdateRepository repository;

    public ScholarUpdateController(ScholarUpdateRepository repository) {
        this.repository = repository;
    }

    @Operation(
        summary = "장학생 정보 수정",
        description = "이름·연락처·학교·전공만 부분 수정합니다. 담당자 전용 API입니다. status·명시적 null 처리는 미완료입니다."
    )
    @PatchMapping(value = "/v1/scholars/{scholarId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ScholarUpdateResponse> updateScholar(
        @PathVariable("scholarId") @Positive long scholarId,
        @Valid @RequestBody ScholarUpdateRequest body, HttpServletRequest request
    ) {
        return ApiResponse.success(repository.update(scholarId, body), request.getRequestURI());
    }
}
