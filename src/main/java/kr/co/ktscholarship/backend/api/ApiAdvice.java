package kr.co.ktscholarship.backend.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
// 공통 응답
public class ApiAdvice {
  // 공통 응답 생성
  public static Map<String, Object> envelope(
      Object data, String code, String message, String path) {
    var m = new LinkedHashMap<String, Object>();
    m.put("resultType", code == null ? "SUCCESS" : "FAIL");
    m.put("success", code == null ? Collections.singletonMap("data", data) : null);
    m.put(
        "error",
        code == null ? null : Map.of("code", code, "message", message, "details", List.of()));
    var meta=new LinkedHashMap<String,Object>();meta.put("timestamp",Instant.now().toString());meta.put("path",path);
    if(AuditContext.current()!=null)meta.put("requestId",AuditContext.current().requestId());m.put("meta",meta);
    return m;
  }

  @ExceptionHandler(ApiException.class)
  // 업무 오류 응답
  ResponseEntity<?> api(ApiException e, HttpServletRequest r) {
    return ResponseEntity.status(e.status)
        .body(envelope(null, e.code, e.getMessage(), r.getRequestURI()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.multipart.MaxUploadSizeExceededException.class
  })
  // 입력 오류 응답
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest()
        .body(envelope(null, "INVALID_REQUEST", "입력값 또는 파일 크기를 확인해 주세요.", r.getRequestURI()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  // 데이터 충돌 응답
  ResponseEntity<?> conflict(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(409)
        .body(envelope(null, "DATA_CONFLICT", "중복 또는 연관된 데이터로 처리할 수 없습니다.", r.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  // 기타 오류 응답
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest r) {
    if (e instanceof org.springframework.web.ErrorResponse error) {
      return ResponseEntity.status(error.getStatusCode())
          .body(envelope(null, "HTTP_ERROR", "요청 경로·메서드·형식을 확인해 주세요.", r.getRequestURI()));
    }
    org.slf4j.LoggerFactory.getLogger(ApiAdvice.class)
        .error("API failure: {}", e.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(envelope(null, "INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.", r.getRequestURI()));
  }
}
