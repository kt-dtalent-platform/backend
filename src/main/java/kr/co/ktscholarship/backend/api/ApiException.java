package kr.co.ktscholarship.backend.api;

// 업무 예외
public class ApiException extends RuntimeException {
  // HTTP 상태
  public final int status;
  // 오류 코드
  public final String code;

  // 예외 정보
  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  // 조건 검증
  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new ApiException(status, code, message);
  }
}
