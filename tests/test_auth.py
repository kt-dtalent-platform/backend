from datetime import timedelta

import jwt
from fastapi import Depends

from app.config import settings
from app.db import utcnow
from app.deps import require_role
from app.main import app
from app.models import Role, User, UserStatus
from app.security import create_access_token
from tests.conftest import auth_header, login


# --- E-AUTH-01 로그인 ---
def test_login_success_returns_token_in_camel_case(client, seed):
    res = login(client, "scholar@example.com")
    assert res.status_code == 200
    data = res.json()["data"]
    assert data["accessToken"]
    assert data["expiresIn"] == 900
    assert data["user"] == {"id": seed["scholar"].id, "name": "김디인", "role": "SCHOLAR"}


def test_login_email_is_case_insensitive(client, seed):
    assert login(client, "Scholar@Example.Com").status_code == 200


def test_wrong_password_and_unknown_email_look_the_same(client, seed):
    wrong_pw = login(client, "scholar@example.com", "wrong-password")
    no_user = login(client, "nobody@example.com", "wrong-password")
    assert wrong_pw.status_code == no_user.status_code == 401
    assert wrong_pw.json()["error"]["code"] == no_user.json()["error"]["code"] == "INVALID_CREDENTIALS"
    assert wrong_pw.json()["error"]["message"] == no_user.json()["error"]["message"]


def test_five_failures_lock_the_account(client, seed):
    for _ in range(5):
        assert login(client, "scholar@example.com", "wrong-password").status_code == 401
    locked = login(client, "scholar@example.com")  # 맞는 비밀번호여도 잠김
    assert locked.status_code == 423
    assert locked.json()["error"]["code"] == "ACCOUNT_LOCKED"
    assert "lockedUntil" in locked.json()["error"]


def test_lock_expires(client, seed, db):
    seed["scholar"].locked_until = utcnow() - timedelta(minutes=1)
    db.commit()
    assert login(client, "scholar@example.com").status_code == 200


def test_pending_invite_account_cannot_login(client, seed):
    # 비밀번호가 아직 없으므로 어떤 비밀번호로도 로그인 불가 (계정 존재도 숨김)
    res = login(client, "invited@example.com", "anything")
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "INVALID_CREDENTIALS"


def test_invalid_email_format_returns_field_errors(client, seed):
    res = client.post("/v1/auth/login", json={"email": "not-an-email", "password": "x"})
    assert res.status_code == 422
    error = res.json()["error"]
    assert error["code"] == "VALIDATION_FAILED"
    assert error["fieldErrors"][0]["field"] == "email"


# --- E-AUTH-04 내 정보 ---
def test_me_requires_token(client, seed):
    res = client.get("/v1/me")
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "UNAUTHENTICATED"


def test_me_rejects_tampered_token(client, seed):
    res = client.get("/v1/me", headers={"Authorization": "Bearer not.a.token"})
    assert res.status_code == 401


def test_me_reports_expired_token(client, seed):
    expired = jwt.encode({"sub": str(seed["scholar"].id), "role": "SCHOLAR", "type": "access",
                          "iat": utcnow() - timedelta(hours=1), "exp": utcnow() - timedelta(minutes=1)},
                         settings.jwt_secret, algorithm=settings.jwt_algorithm)
    res = client.get("/v1/me", headers={"Authorization": f"Bearer {expired}"})
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "TOKEN_EXPIRED"


def test_me_for_scholar_has_cohort(client, seed):
    res = client.get("/v1/me", headers=auth_header(client, "scholar@example.com"))
    assert res.status_code == 200
    data = res.json()["data"]
    assert data["role"] == "SCHOLAR"
    assert data["scholar"] == {"programId": seed["program"].id, "programName": "KT디지털인재장학",
                               "cohortId": seed["cohort5"].id, "cohortName": "5기", "schoolName": "OO대학교"}
    assert data["manager"] is None


def test_me_for_manager_lists_accessible_cohorts(client, seed):
    res = client.get("/v1/me", headers=auth_header(client, "manager@example.com"))
    data = res.json()["data"]
    assert data["scholar"] is None
    programs = data["manager"]["accessiblePrograms"]
    assert programs == [{"programId": seed["program"].id, "name": "KT디지털인재장학",
                         "cohortIds": [seed["cohort5"].id, seed["cohort6"].id]}]


# --- 역할 검사 (인가 ①) ---
@app.get("/v1/_test/manager-only")
def _manager_only(user: User = Depends(require_role(Role.MANAGER))):
    return {"data": {"ok": True}}


def test_scholar_gets_403_on_manager_api(client, seed):
    res = client.get("/v1/_test/manager-only", headers=auth_header(client, "scholar@example.com"))
    assert res.status_code == 403
    assert res.json()["error"]["code"] == "FORBIDDEN_ROLE"


def test_manager_passes_role_check(client, seed):
    res = client.get("/v1/_test/manager-only", headers=auth_header(client, "manager@example.com"))
    assert res.status_code == 200


def test_token_of_deactivated_user_is_rejected(client, seed, db):
    token = create_access_token(seed["scholar"].id, "SCHOLAR")
    seed["scholar"].status = UserStatus.INACTIVE
    db.commit()
    res = client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 401
