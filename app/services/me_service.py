"""E-AUTH-04 내 계정 정보. 장학생은 소속 기수, 담당자는 접근 가능한 프로그램·기수를 채운다."""
from app.models import Role, User
from app.schemas import AccessibleProgram, ManagerInfo, MeOut, ScholarInfo


def build_me(user: User) -> MeOut:
    me = MeOut(id=user.id, email=user.email, name=user.name, role=user.role, status=user.status)

    if user.role == Role.SCHOLAR and user.scholar_profile is not None:
        profile = user.scholar_profile
        me.scholar = ScholarInfo(
            program_id=profile.cohort.program_id,
            program_name=profile.cohort.program.name,
            cohort_id=profile.cohort_id,
            cohort_name=profile.cohort.name,
            school_name=profile.school_name,
        )

    if user.role == Role.MANAGER:
        # 프로그램별로 묶는다. 한 행이라도 cohort_id가 NULL이면 그 프로그램 전체 기수 접근
        programs: dict[int, AccessibleProgram] = {}
        for scope in user.managed_programs:
            item = programs.setdefault(
                scope.program_id,
                AccessibleProgram(program_id=scope.program_id, name=scope.program.name, cohort_ids=[]),
            )
            if scope.cohort_id is None:
                item.cohort_ids = None
            elif item.cohort_ids is not None:
                item.cohort_ids.append(scope.cohort_id)
        me.manager = ManagerInfo(accessible_programs=list(programs.values()))

    return me
