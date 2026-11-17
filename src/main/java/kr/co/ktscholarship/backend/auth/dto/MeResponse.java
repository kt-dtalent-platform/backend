package kr.co.ktscholarship.backend.auth.dto;

import kr.co.ktscholarship.backend.user.User;
import java.util.List;

// 담당자 프로그램 목록은 유지하고, 장학생의 기수·학교는 연결부에서 조회해 전달한다.
public record MeResponse(
        Long id,
        String name,
        String email,
        String role,
        Cohort cohort,
        List<Program> accessiblePrograms,
        String university
) {

    public static MeResponse from(User user, List<Program> accessiblePrograms) {
        return from(user, accessiblePrograms, null);
    }

    public static MeResponse from(User user, List<Program> programs, ScholarProfile profile) {
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().name(),
            profile == null ? null : profile.cohort(), programs, profile == null ? null : profile.university());
    }

    public record Program(Long id, String name) { }
    public record Cohort(Long id, String name) { }
    public record ScholarProfile(Cohort cohort, String university) { }
}
