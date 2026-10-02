from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import models  # noqa: F401  테이블 등록
from app.db import Base, engine
from app.errors import register_error_handlers
from app.routers import auth, health


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 개발 단계: 테이블 자동 생성. ERD 확정 후 Alembic 마이그레이션으로 교체 예정
    Base.metadata.create_all(engine)
    yield


app = FastAPI(
    title="KT디인재 통합운영 플랫폼 API",
    version="0.1.0",
    description="성적증명서 제출·검토 MVP. 명세: API 명세 정리본 v1.1 (MVP 37)",
    lifespan=lifespan,
)
register_error_handlers(app)

API_PREFIX = "/v1"
app.include_router(health.router, prefix=API_PREFIX)
app.include_router(auth.router, prefix=API_PREFIX)
