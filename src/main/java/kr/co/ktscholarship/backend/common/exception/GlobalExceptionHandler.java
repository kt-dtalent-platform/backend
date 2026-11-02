package kr.co.ktscholarship.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import kr.co.ktscholarship.backend.common.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.status())
                .body(ApiResponse.failure(
                        errorCode.code(),
                        errorCode.message(),
                        exception.getDetails(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<String> invalidFields = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .toList();

        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(
                        ErrorCode.INVALID_REQUEST.code(),
                        ErrorCode.INVALID_REQUEST.message(),
                        invalidFields,
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure(
                        ErrorCode.INTERNAL_SERVER_ERROR.code(),
                        ErrorCode.INTERNAL_SERVER_ERROR.message(),
                        null,
                        request.getRequestURI()
                ));
    }
}
