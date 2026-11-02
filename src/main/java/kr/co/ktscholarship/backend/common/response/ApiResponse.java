package kr.co.ktscholarship.backend.common.response;

public record ApiResponse<T>(
        ResultType resultType,
        ApiSuccess<T> success,
        ApiError error,
        ApiMeta meta
) {

    public static <T> ApiResponse<T> success(T data, String path) {
        return new ApiResponse<>(
                ResultType.SUCCESS,
                new ApiSuccess<>(data),
                null,
                ApiMeta.from(path)
        );
    }

    public static ApiResponse<Void> failure(String code, String message, Object details, String path) {
        return new ApiResponse<>(
                ResultType.FAIL,
                null,
                new ApiError(code, message, details),
                ApiMeta.from(path)
        );
    }
}
