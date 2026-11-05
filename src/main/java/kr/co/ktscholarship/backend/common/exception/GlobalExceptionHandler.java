package kr.co.ktscholarship.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import kr.co.ktscholarship.backend.common.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<String> invalidFields = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .toList();

        return createResponseEntity(
                ApiResponse.failure(
                        ErrorCode.INVALID_REQUEST.code(),
                        ErrorCode.INVALID_REQUEST.message(),
                        invalidFields,
                        requestPath(request)
                ),
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ErrorCode errorCode = errorCodeFor(status);
        return super.handleExceptionInternal(
                exception,
                ApiResponse.failure(
                        errorCode.code(),
                        errorCode.message(),
                        null,
                        requestPath(request)
                ),
                headers,
                status,
                request
        );
    }

    // 보안 예외는 응답을 만들지 않고 다시 던져 Security 필터(401·403 응답기)가 처리하게 한다
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException exception) {
        throw exception;
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

    private ErrorCode errorCodeFor(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return ErrorCode.INVALID_REQUEST;
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            return ErrorCode.UNAUTHORIZED;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
            return ErrorCode.ACCESS_DENIED;
        }
        return ErrorCode.INTERNAL_SERVER_ERROR;
    }

    private String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }
}
