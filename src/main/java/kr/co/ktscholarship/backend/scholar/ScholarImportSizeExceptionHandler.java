package kr.co.ktscholarship.backend.scholar;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Multipart 크기 제한은 Controller가 선택되기 전에 발생하므로 전역에서 이 예외만 처리한다.
@Order(-1)
@RestControllerAdvice
public class ScholarImportSizeExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(HttpServletRequest request) {
        return ResponseEntity.status(413).body(ApiResponse.failure(
            ErrorCode.INVALID_REQUEST.code(), "업로드 파일 크기 제한을 초과했습니다.",
            List.of(new ScholarImportExcel.RowError(0, "file", "1MB 이하의 파일을 올려 주세요.")), request.getRequestURI()));
    }
}
