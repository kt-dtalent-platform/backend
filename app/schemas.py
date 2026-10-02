"""요청·응답 모델. JSON은 camelCase (D-13 제안안), 파이썬 코드는 snake_case."""
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, EmailStr, Field
from pydantic.alias_generators import to_camel

from app.models import Role, UserStatus

T = TypeVar("T")


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, serialize_by_alias=True)


class Envelope(BaseModel, Generic[T]):
    """성공 응답 공통 형식: { "data": ... }"""

    data: T


# --- E-AUTH-01 로그인 ---
class LoginIn(CamelModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class LoginUser(CamelModel):
    id: int
    name: str
    role: Role


class LoginOut(CamelModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    user: LoginUser


# --- E-AUTH-04 내 계정 정보 ---
class ScholarInfo(CamelModel):
    program_id: int
    program_name: str
    cohort_id: int
    cohort_name: str
    school_name: str


class AccessibleProgram(CamelModel):
    program_id: int
    name: str
    cohort_ids: list[int] | None  # None = 프로그램 전체 기수


class ManagerInfo(CamelModel):
    accessible_programs: list[AccessibleProgram]


class MeOut(CamelModel):
    id: int
    email: str
    name: str
    role: Role
    status: UserStatus
    scholar: ScholarInfo | None = None
    manager: ManagerInfo | None = None
