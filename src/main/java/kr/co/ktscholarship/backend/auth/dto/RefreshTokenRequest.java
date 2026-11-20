package kr.co.ktscholarship.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(@NotBlank @Size(max = 200) String refreshToken) {
    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=****]";
    }
}
