package kr.co.ktscholarship.backend.task;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.auth.security.CurrentUserId;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MyTaskController {

    private final MyTaskRepository repository;

    public MyTaskController(MyTaskRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "내 할 일 목록", description = "본인의 진행 중 요청을 조회합니다. 현재 dueAt은 공통 마감일이며 dDay는 UTC 날짜 기준입니다. 개별 연장 마감일은 저장 구조 반영 후 연결합니다.")
    @GetMapping("/v1/me/tasks")
    public ApiResponse<MyTaskResponse> getTasks(
        @CurrentUserId Long userId,
        @RequestParam(name = "status", defaultValue = "TODO") MyTaskRepository.Filter status,
        @RequestParam(name = "sort", defaultValue = "DUE_ASC") MyTaskRepository.Sort sort,
        HttpServletRequest request
    ) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var items = repository.find(userId, status, sort).stream()
            .map(task -> new MyTaskResponse.Item(
                task.requestId(), task.title(), task.requestDueAt(),
                ChronoUnit.DAYS.between(today, task.requestDueAt().toLocalDate()),
                task.status(), task.revisionReason()
            )).toList();
        return ApiResponse.success(new MyTaskResponse(items), request.getRequestURI());
    }
}
