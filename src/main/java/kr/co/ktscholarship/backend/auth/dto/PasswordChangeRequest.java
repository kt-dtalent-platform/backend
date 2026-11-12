package kr.co.ktscholarship.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// BCrypt는 72바이트까지만 처리하므로 새 비밀번호는 64자로 제한해 500 대신 400으로 거른다
public record PasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {

    // 로그·디버거에 비밀번호가 찍히지 않게 한다
    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=****, newPassword=****]";
    }
}
