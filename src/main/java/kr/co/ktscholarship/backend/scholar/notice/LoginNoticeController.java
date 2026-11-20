package kr.co.ktscholarship.backend.scholar.notice;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.ktscholarship.backend.config.OpenApiConfig;
import static kr.co.ktscholarship.backend.scholar.notice.LoginNoticeModels.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = OpenApiConfig.TAG_SCHOLAR)
@RestController
@PreAuthorize("hasRole('MANAGER')")
class LoginNoticeController {
    private final LoginNoticeService service;
    LoginNoticeController(LoginNoticeService service) { this.service=service; }
    @Operation(summary="로그인 안내 공용 발신 주소 조회")
    @GetMapping("/v1/scholars/login-notices/sender")
    ApiResponse<Sender> sender(HttpServletRequest request) { return ApiResponse.success(service.sender(),request.getRequestURI()); }
    @Operation(summary="로그인 안내 공용 발신 주소 수정",description="SMTP가 허용한 주소만 사용해야 합니다. SMTP 접속 비밀값은 서버 환경변수로 관리합니다.")
    @PutMapping("/v1/scholars/login-notices/sender")
    ApiResponse<Sender> sender(@Valid @RequestBody Sender body,HttpServletRequest request) {
        service.saveSender(body);return ApiResponse.success(body,request.getRequestURI());
    }
    @Operation(summary="학생 선택 로그인 안내 발송",description="""
        등록과 분리된 수동 발송. 최대 500명. 202는 발송 접수이며 결과 API에서 SMTP 처리 결과를 확인합니다.
        reissue=false(기본)는 이미 발송한 대상을 건너뜁니다. 명시적 reissue=true는 임시 비밀번호를 새로 발급합니다.
        첫 발송은 이미 비밀번호를 변경한 학생을 건너뜁니다. reissue=true는 분실한 본인 비밀번호도 임시 비밀번호로 교체합니다.
        기존 비밀번호는 새 로그인에 사용할 수 없지만 기존 JWT는 현재 인증 정책대로 만료까지 유지됩니다. 강제 로그아웃은 별도 인증 연동입니다.
        SENT는 SMTP 서버 접수 성공이며 받은편지함 도착·열람을 보장하지 않습니다. 비밀번호 원문은 응답하지 않습니다.
        """, responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="발송 접수, 결과는 조회 API로 확인"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="INVALID_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ACCESS_DENIED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="MAIL_UNAVAILABLE")
    })
    @PostMapping("/v1/scholars/login-notices")
    ResponseEntity<ApiResponse<Result>> send(@Valid @RequestBody SendRequest body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(service.enqueue(body,Long.parseLong(jwt.getSubject())),request.getRequestURI()));
    }
    @Operation(summary="선택한 학생의 로그인 안내 발송 상태",description="PENDING/SENDING/SENT/FAILED. 실패·불확실 건은 담당자가 확인 후 다시 발급합니다.")
    @GetMapping("/v1/scholars/login-notices")
    ApiResponse<List<Item>> results(@RequestParam("scholarIds") List<Long> ids,HttpServletRequest request) {
        return ApiResponse.success(service.results(ids),request.getRequestURI());
    }
}
