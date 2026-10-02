import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str = os.getenv("DATABASE_URL", "sqlite:///./dev.db")
    # 개발용 기본값. 배포 환경에서는 반드시 JWT_SECRET 환경변수로 교체
    jwt_secret: str = os.getenv("JWT_SECRET", "dev-only-secret-do-not-use-in-production-0001")
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = int(os.getenv("ACCESS_TOKEN_MINUTES", "15"))
    login_max_failures: int = int(os.getenv("LOGIN_MAX_FAILURES", "5"))
    login_lock_minutes: int = int(os.getenv("LOGIN_LOCK_MINUTES", "15"))


settings = Settings()
