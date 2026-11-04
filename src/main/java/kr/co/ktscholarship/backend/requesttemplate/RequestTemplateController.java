package kr.co.ktscholarship.backend.requesttemplate;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RequestTemplateController {

    private final RequestTemplateRepository repository;

    public RequestTemplateController(RequestTemplateRepository repository) {
        this.repository = repository;
    }

    @Operation(
        summary = "요청 유형 템플릿 목록",
        description = "저장된 템플릿과 기본 필드를 조회합니다. 프리셋 15종 정의·비활성 처리·정렬·권한 연결은 확인이 필요합니다."
    )
    @GetMapping(value = "/v1/request-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RequestTemplateListResponse> getTemplates(HttpServletRequest request) {
        return ApiResponse.success(repository.findAll(), request.getRequestURI());
    }
}
