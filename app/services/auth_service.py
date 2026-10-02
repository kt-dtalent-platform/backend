"""로그인 규칙 (E-AUTH-01)
- 이메일이 없거나 비밀번호가 틀리면 같은 응답(401 INVALID_CREDENTIALS) → 계정 존재 여부를 숨김
- 5회 연속 실패 시 15분 잠금(423 ACCOUNT_LOCKED)
- 비밀번호는 맞지만 활성 계정이 아니면 403 ACCOUNT_NOT_ACTIVE
"""
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.db import as_utc, utcnow
from app.errors import AppError
from app.models import User, UserStatus
from app.security import create_access_token, verify_password


def _invalid_credentials() -> AppError:
    return AppError(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.")


def login(db: Session, email: str, password: str) -> tuple[User, str]:
    user = db.scalar(select(User).where(User.email == email.lower(), User.deleted_at.is_(None)))
    now = utcnow()

    if user is None:
        verify_password(password, None)  # 응답 시간 맞추기
        raise _invalid_credentials()

    locked_until = as_utc(user.locked_until)
    if locked_until and locked_until > now:
        raise AppError(423, "ACCOUNT_LOCKED", "로그인 시도가 많아 잠시 잠겼습니다.",
                       extra={"lockedUntil": locked_until.isoformat()})

    if not verify_password(password, user.password_hash):
        user.failed_login_count += 1
        if user.failed_login_count >= settings.login_max_failures:
            user.locked_until = now + timedelta(minutes=settings.login_lock_minutes)
            user.failed_login_count = 0
        db.commit()
        raise _invalid_credentials()

    if user.status != UserStatus.ACTIVE:
        raise AppError(403, "ACCOUNT_NOT_ACTIVE", "사용할 수 없는 계정입니다. 담당자에게 문의해 주세요.")

    user.failed_login_count = 0
    user.locked_until = None
    user.last_login_at = now
    db.commit()
    return user, create_access_token(user.id, user.role.value)
