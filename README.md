# KT디인재 통합운영 플랫폼 — Backend

성적증명서 제출·검토 MVP의 API 서버 (FastAPI · SQLAlchemy · SQLite → PostgreSQL 예정)

- 명세: API 명세 정리본 v1.1 (MVP 37개, `E-` ID 기준)
- ERD 기준: erdcloud `KTScholarship_platform` (+ 10/2 회의 결정 반영 예정)

## 실행

```bash
python -m venv .venv
.venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python -m scripts.seed          # 개발용 테스트 계정 생성 (계정 정보는 scripts/seed.py)
uvicorn app.main:app --reload
```

- Swagger 문서: http://localhost:8000/docs
- 테스트: `pytest`

설정은 환경변수로 바꿉니다 (`.env.example` 참고). 배포 환경에서는 `JWT_SECRET`을 반드시 교체하세요.

## 구현 현황

| ID | API | 상태 |
|---|---|---|
| — | `GET /v1/health` | ✅ |
| E-AUTH-01 | `POST /v1/auth/login` | ✅ 5회 실패 잠금, 계정 존재 여부 비노출 |
| E-AUTH-04 | `GET /v1/me` | ✅ 장학생=소속 기수, 담당자=접근 가능 프로그램·기수 |
| E-AUTH-02·03 | 로그아웃 · 토큰 재발급 | ⬜ W2 |
| E-REQ-02·06 | 요청 생성 · 공개 | ⬜ W2 |

공통 규칙: 성공 `{ "data": ... }` / 실패 `{ "error": { "code", "message", "fieldErrors", "traceId" } }`, JSON은 camelCase

## 구조

```
app/
  main.py            앱 생성, 라우터 등록, /v1 prefix
  config.py          환경변수 설정
  db.py              DB 연결, 세션
  models.py          테이블 (users, scholar_profiles, programs, cohorts, manager_programs)
  schemas.py         요청·응답 모델 (camelCase)
  security.py        비밀번호 해시(argon2), JWT
  deps.py            로그인 사용자, 역할 검사(401/403)
  errors.py          공통 에러 응답
  routers/           URL ↔ 함수 연결 (얇게)
  services/          비즈니스 규칙 (로그인 잠금, 내 정보 조립)
scripts/seed.py      개발용 시드
tests/               pytest
```

## 10/2 회의 결정 대기 (코드에 제안안으로 반영된 부분)

- `users.status`에 `PENDING_INVITE`, `password_hash` NULL 허용 (D-04)
- `manager_programs` 테이블 — 기수 NULL이면 프로그램 전체 (D-05)
- 역할 코드 `MANAGER` (D-03)

## 규칙

- 실제 성적증명서·개인정보는 이 저장소에 올리지 않습니다
- `main` 직접 push 금지, PR로 병합
