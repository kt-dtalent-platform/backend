package kr.co.ktscholarship.backend.scholar;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.response.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = ScholarImportController.class)
public class ScholarBulkValidationExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalidRequest(
        MethodArgumentNotValidException exception, HttpServletRequest request
    ) {
        List<Object> details = new ArrayList<>();
        for (var error : exception.getBindingResult().getFieldErrors()) {
            String field = error.getField();
            if (field.startsWith("scholars[") && field.contains("]")) {
                int index = Integer.parseInt(field.substring(9, field.indexOf(']')));
                details.add(new ScholarImportService.RowError(index + 2, "scholars", "장학생 정보를 입력해 주세요."));
            } else if (!details.contains(field)) {
                details.add(field);
            }
        }
        return ResponseEntity.badRequest().body(ApiResponse.failure(
            ErrorCode.INVALID_REQUEST.code(), ErrorCode.INVALID_REQUEST.message(), details, request.getRequestURI()));
    }
}
