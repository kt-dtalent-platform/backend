"""개발용 시드 데이터. 실행: python -m scripts.seed

아래 계정은 로컬 개발·데모 전용 테스트 계정이다. 실제 장학생 정보·실제 비밀번호를 넣지 않는다.
"""
from sqlalchemy import select

from app import models  # noqa: F401
from app.db import Base, SessionLocal, engine
from app.models import Cohort, ManagerProgram, Program, Role, ScholarProfile, User, UserStatus
from app.security import hash_password

DEV_ACCOUNTS = {
    "manager": {"email": "manager@example.com", "password": "Manager!2026", "name": "재단담당"},
    "scholar": {"email": "scholar@example.com", "password": "Scholar!2026", "name": "김디인"},
    "invited": {"email": "invited@example.com", "password": None, "name": "이초대"},  # 초대 대기 (D-04)
}


def run() -> None:
    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        if db.scalar(select(User).where(User.email == DEV_ACCOUNTS["manager"]["email"])):
            print("이미 시드 데이터가 있습니다.")
            return

        program = Program(name="KT디지털인재장학")
        cohort = Cohort(program=program, name="5기")
        db.add_all([program, cohort])
        db.flush()

        manager = User(email=DEV_ACCOUNTS["manager"]["email"], name=DEV_ACCOUNTS["manager"]["name"],
                       password_hash=hash_password(DEV_ACCOUNTS["manager"]["password"]), role=Role.MANAGER)
        scholar = User(email=DEV_ACCOUNTS["scholar"]["email"], name=DEV_ACCOUNTS["scholar"]["name"],
                       password_hash=hash_password(DEV_ACCOUNTS["scholar"]["password"]), role=Role.SCHOLAR)
        invited = User(email=DEV_ACCOUNTS["invited"]["email"], name=DEV_ACCOUNTS["invited"]["name"],
                       password_hash=None, role=Role.SCHOLAR, status=UserStatus.PENDING_INVITE)
        db.add_all([manager, scholar, invited])
        db.flush()

        db.add_all([
            ScholarProfile(user_id=scholar.id, cohort_id=cohort.id, school_name="OO대학교"),
            ScholarProfile(user_id=invited.id, cohort_id=cohort.id, school_name="△△대학교"),
            ManagerProgram(manager_id=manager.id, program_id=program.id, cohort_id=None),  # 프로그램 전체
        ])
        db.commit()
        print("시드 완료: 담당자 1, 장학생 1, 초대 대기 1 (계정 정보는 scripts/seed.py 참고)")


if __name__ == "__main__":
    run()
