"""ERD(erdcloud KTScholarship_platform) 기준 모델 중 인증에 필요한 부분.

ERD와 다른 점 (10/2 회의 결정 대기):
- users.status에 PENDING_INVITE 추가, password_hash NULL 허용  (D-04)
- manager_programs 신규                                       (D-05)
"""
import enum
from datetime import date, datetime

from sqlalchemy import BigInteger, Date, DateTime, Enum, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base, utcnow

# SQLite는 BIGINT 자동 증가를 지원하지 않아 SQLite에서는 INTEGER로 매핑
BigId = BigInteger().with_variant(Integer(), "sqlite")


class Role(str, enum.Enum):
    MANAGER = "MANAGER"
    SCHOLAR = "SCHOLAR"


class UserStatus(str, enum.Enum):
    PENDING_INVITE = "PENDING_INVITE"
    ACTIVE = "ACTIVE"
    INACTIVE = "INACTIVE"
    LOCKED = "LOCKED"


class ScholarStatus(str, enum.Enum):
    ENROLLED = "ENROLLED"
    LEAVE = "LEAVE"
    GRADUATED = "GRADUATED"
    WITHDRAWN = "WITHDRAWN"


class Program(Base):
    __tablename__ = "programs"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    description: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class Cohort(Base):
    __tablename__ = "cohorts"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    program_id: Mapped[int] = mapped_column(ForeignKey("programs.id"))
    name: Mapped[str] = mapped_column(String(50))
    started_on: Mapped[date | None] = mapped_column(Date)
    ended_on: Mapped[date | None] = mapped_column(Date)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    program: Mapped[Program] = relationship()


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str | None] = mapped_column(String(255))  # 초대 수락 전 NULL (D-04)
    name: Mapped[str] = mapped_column(String(50))
    role: Mapped[Role] = mapped_column(Enum(Role, native_enum=False, length=20))
    status: Mapped[UserStatus] = mapped_column(
        Enum(UserStatus, native_enum=False, length=20), default=UserStatus.ACTIVE
    )
    failed_login_count: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    scholar_profile: Mapped["ScholarProfile | None"] = relationship(back_populates="user")
    managed_programs: Mapped[list["ManagerProgram"]] = relationship(back_populates="manager")


class ScholarProfile(Base):
    __tablename__ = "scholar_profiles"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    cohort_id: Mapped[int] = mapped_column(ForeignKey("cohorts.id"))
    school_name: Mapped[str] = mapped_column(String(100))
    major: Mapped[str | None] = mapped_column(String(100))
    student_no: Mapped[str | None] = mapped_column(String(30))
    phone: Mapped[str | None] = mapped_column(String(20))
    status: Mapped[ScholarStatus] = mapped_column(
        Enum(ScholarStatus, native_enum=False, length=20), default=ScholarStatus.ENROLLED
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    user: Mapped[User] = relationship(back_populates="scholar_profile")
    cohort: Mapped[Cohort] = relationship()


class ManagerProgram(Base):
    """담당자 접근 범위 (D-05 제안안). cohort_id가 NULL이면 프로그램 전체."""

    __tablename__ = "manager_programs"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    manager_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    program_id: Mapped[int] = mapped_column(ForeignKey("programs.id"))
    cohort_id: Mapped[int | None] = mapped_column(ForeignKey("cohorts.id"))

    manager: Mapped[User] = relationship(back_populates="managed_programs")
    program: Mapped[Program] = relationship()
