import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import models  # noqa: F401
from app.db import Base, get_db
from app.main import app
from app.models import Cohort, ManagerProgram, Program, Role, ScholarProfile, User, UserStatus
from app.security import hash_password

PASSWORD = "Test!pass123"


@pytest.fixture()
def db():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    TestingSession = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    session = TestingSession()
    app.dependency_overrides[get_db] = lambda: session
    yield session
    session.close()
    app.dependency_overrides.clear()


@pytest.fixture()
def client(db):
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def seed(db):
    program = Program(name="KT디지털인재장학")
    cohort5 = Cohort(program=program, name="5기")
    cohort6 = Cohort(program=program, name="6기")
    db.add_all([program, cohort5, cohort6])
    db.flush()

    manager = User(email="manager@example.com", name="담당자", password_hash=hash_password(PASSWORD), role=Role.MANAGER)
    scholar = User(email="scholar@example.com", name="김디인", password_hash=hash_password(PASSWORD), role=Role.SCHOLAR)
    invited = User(email="invited@example.com", name="이초대", password_hash=None, role=Role.SCHOLAR,
                   status=UserStatus.PENDING_INVITE)
    db.add_all([manager, scholar, invited])
    db.flush()
    db.add_all([
        ScholarProfile(user_id=scholar.id, cohort_id=cohort5.id, school_name="OO대학교"),
        ManagerProgram(manager_id=manager.id, program_id=program.id, cohort_id=cohort5.id),
        ManagerProgram(manager_id=manager.id, program_id=program.id, cohort_id=cohort6.id),
    ])
    db.commit()
    return {"manager": manager, "scholar": scholar, "invited": invited, "program": program,
            "cohort5": cohort5, "cohort6": cohort6}


def login(client: TestClient, email: str, password: str = PASSWORD):
    return client.post("/v1/auth/login", json={"email": email, "password": password})


def auth_header(client: TestClient, email: str) -> dict:
    token = login(client, email).json()["data"]["accessToken"]
    return {"Authorization": f"Bearer {token}"}
