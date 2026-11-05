package kr.co.ktscholarship.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password
) {

    // 로그·디버거에 비밀번호가 찍히지 않게 한다
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
