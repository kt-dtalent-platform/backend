"""인증·인가 의존성. 인가 3단 검사 중 ① 역할(Role)을 여기서 처리한다.
② 담당 범위(manager_programs), ③ 본인 소유 검사는 각 기능의 서비스 계층에서 한다."""
from collections.abc import Callable

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.db import get_db
from app.errors import AppError
from app.models import Role, User, UserStatus
from app.security import decode_access_token

_bearer = HTTPBearer(auto_error=False)


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
) -> User:
    if credentials is None:
        raise AppError(401, "UNAUTHENTICATED", "로그인이 필요합니다.")
    try:
        payload = decode_access_token(credentials.credentials)
    except jwt.ExpiredSignatureError:
        raise AppError(401, "TOKEN_EXPIRED", "로그인이 만료되었습니다. 다시 로그인해 주세요.")
    except jwt.InvalidTokenError:
        raise AppError(401, "UNAUTHENTICATED", "로그인이 필요합니다.")

    user = db.get(User, int(payload["sub"]))
    if user is None or user.deleted_at is not None or user.status != UserStatus.ACTIVE:
        raise AppError(401, "UNAUTHENTICATED", "로그인이 필요합니다.")
    return user


def require_role(*roles: Role) -> Callable[..., User]:
    """사용 예: user: User = Depends(require_role(Role.MANAGER))"""

    def _checker(user: User = Depends(get_current_user)) -> User:
        if user.role not in roles:
            raise AppError(403, "FORBIDDEN_ROLE", "이 기능을 사용할 권한이 없습니다.")
        return user

    return _checker
