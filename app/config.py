import os
from dataclasses import dataclass
from pathlib import Path

# 어느 폴더에서 실행해도 같은 개발용 DB를 쓰도록 프로젝트 루트 기준 경로를 기본값으로
_DEFAULT_DB = f"sqlite:///{(Path(__file__).resolve().parent.parent / 'dev.db').as_posix()}"


@dataclass(frozen=True)
class Settings:
    database_url: str = os.getenv("DATABASE_URL", _DEFAULT_DB)
    # 개발용 기본값. 배포 환경에서는 반드시 JWT_SECRET 환경변수로 교체
    jwt_secret: str = os.getenv("JWT_SECRET", "dev-only-secret-do-not-use-in-production-0001")
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = int(os.getenv("ACCESS_TOKEN_MINUTES", "15"))
    login_max_failures: int = int(os.getenv("LOGIN_MAX_FAILURES", "5"))
    login_lock_minutes: int = int(os.getenv("LOGIN_LOCK_MINUTES", "15"))


settings = Settings()
