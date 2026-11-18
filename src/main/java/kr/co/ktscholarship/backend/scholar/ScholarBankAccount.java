package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.constraints.*;

public record ScholarBankAccount(
    @NotBlank @Size(max = 50) String bankName,
    @NotBlank @Size(min = 5, max = 50) @Pattern(regexp = "[0-9 -]+") String accountNumber,
    @NotBlank @Size(max = 50) String accountHolder
) {
    @Override public String toString() { return "ScholarBankAccount[redacted]"; }
}
