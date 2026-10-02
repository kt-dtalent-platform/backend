from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_db
from app.deps import get_current_user
from app.models import User
from app.schemas import Envelope, LoginIn, LoginOut, LoginUser, MeOut
from app.services import auth_service, me_service

router = APIRouter(tags=["auth"])

_ERRORS_LOGIN = {
    401: {"description": "INVALID_CREDENTIALS — 이메일 없음·비밀번호 틀림 (같은 응답)"},
    403: {"description": "ACCOUNT_NOT_ACTIVE — 초대 대기·비활성 계정"},
    422: {"description": "VALIDATION_FAILED — 이메일 형식 등"},
    423: {"description": "ACCOUNT_LOCKED — 5회 연속 실패, lockedUntil 포함"},
}


@router.post("/auth/login", response_model=Envelope[LoginOut], summary="E-AUTH-01 로그인", responses=_ERRORS_LOGIN)
def login(body: LoginIn, db: Session = Depends(get_db)):
    user, token = auth_service.login(db, body.email, body.password)
    return Envelope(data=LoginOut(
        access_token=token,
        expires_in=settings.access_token_minutes * 60,
        user=LoginUser(id=user.id, name=user.name, role=user.role),
    ))


@router.get("/me", response_model=Envelope[MeOut], summary="E-AUTH-04 내 계정 정보",
            responses={401: {"description": "UNAUTHENTICATED / TOKEN_EXPIRED"}})
def me(user: User = Depends(get_current_user)):
    return Envelope(data=me_service.build_me(user))
