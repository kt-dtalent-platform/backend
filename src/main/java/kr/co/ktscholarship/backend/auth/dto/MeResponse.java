package kr.co.ktscholarship.backend.auth.dto;

import kr.co.ktscholarship.backend.user.User;
import java.util.List;

// cohort는 기존 응답을 유지하고, 담당자에게 전체 프로그램 목록을 제공한다.
public record MeResponse(
        Long id,
        String name,
        String email,
        String role,
        Object cohort,
        List<Program> accessiblePrograms
) {

    public static MeResponse from(User user, List<Program> accessiblePrograms) {
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().name(), null, accessiblePrograms);
    }

    public record Program(Long id, String name) { }
}
