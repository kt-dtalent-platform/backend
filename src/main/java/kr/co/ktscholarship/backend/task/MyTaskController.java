package kr.co.ktscholarship.backend.task;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MyTaskController {

    private final MyTaskService service;

    public MyTaskController(MyTaskService service) {
        this.service = service;
    }

    @Operation(summary = "내 할 일 목록", description = "본인의 진행 중 요청을 조회합니다. 현재 dueAt은 공통 마감일이며 dDay는 한국 날짜 기준입니다. 개별 연장 마감일은 저장 구조 반영 후 연결합니다.")
    @GetMapping("/v1/me/tasks")
    public ApiResponse<MyTaskResponse> getTasks(
        @CurrentUserId Long userId,
        @RequestParam(name = "status", defaultValue = "TODO") MyTaskRepository.Filter status,
        @RequestParam(name = "sort", defaultValue = "DUE_ASC") MyTaskRepository.Sort sort,
        HttpServletRequest request
    ) {
        return ApiResponse.success(service.find(userId, status, sort), request.getRequestURI());
    }
}
