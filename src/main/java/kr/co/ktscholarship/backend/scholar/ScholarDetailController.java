package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScholarDetailController {

    private final ScholarDetailRepository repository;

    public ScholarDetailController(ScholarDetailRepository repository) {
        this.repository = repository;
    }

    @Operation(
        summary = "장학생 상세 조회",
        description = "담당자 전용 API입니다. 제출 요약은 공개된 배정 요청 기준이며 pending은 total - confirmed입니다. 계좌번호는 미구현입니다.",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "SCHOLAR_NOT_FOUND - 장학생을 찾을 수 없습니다.")
        }
    )
    @GetMapping(value = "/v1/scholars/{scholarId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ScholarDetailResponse> getScholar(
        @PathVariable("scholarId") @Positive long scholarId, HttpServletRequest request
    ) {
        var scholar = repository.findById(scholarId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND));
        return ApiResponse.success(scholar, request.getRequestURI());
    }
}
