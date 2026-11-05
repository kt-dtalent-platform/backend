package kr.co.ktscholarship.backend.auth.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 현재 로그인한 사용자 id(토큰의 sub)를 Controller 파라미터로 받는다.
 * 사용 예: {@code public ApiResponse<...> me(@CurrentUserId Long userId)}
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "T(java.lang.Long).valueOf(subject)")
public @interface CurrentUserId {
}
