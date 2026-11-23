package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ScholarCreateRequest(
    @NotNull @Positive Long cohortId,
    @NotBlank @Size(max = 50) String name,
    @NotBlank @Email @Size(max = 255) String email,
    @Schema(description = "선택 연락처. 숫자 이외 문자를 제거한 뒤 0으로 시작하는 9~11자리여야 합니다.")
    @Pattern(regexp = "0[0-9]{8,10}") String phone,
    @Schema(description = "필수 학교명. schoolId를 선택하면 서버가 목록의 이름으로 저장합니다.")
    @NotBlank @Size(max = 100) String university,
    @Size(max = 100) String major,
    @Size(max = 30) String studentNo,
    @Schema(description = "목록에서 선택한 학교 ID. 직접 입력은 null 또는 생략합니다.")
    @Positive Long schoolId
) {
    public ScholarCreateRequest {
        phone = ScholarPhones.digits(phone);
    }
}
