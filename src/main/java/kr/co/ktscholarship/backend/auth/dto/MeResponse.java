package kr.co.ktscholarship.backend.auth.dto;

import kr.co.ktscholarship.backend.user.User;

// cohort·accessiblePrograms는 명세 필드를 유지하되 MVP에서는 null
public record MeResponse(
        Long id,
        String name,
        String email,
        String role,
        Object cohort,
        Object accessiblePrograms
) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().name(), null, null);
    }
}
