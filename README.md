# KT 장학 플랫폼 백엔드

장학생의 서류 제출과 담당자의 검토를 처리할 서버입니다.

**현재: 서버·DB 연결·테이블 생성 완료. 로그인·제출·OCR 기능은 아직 없습니다.**

## 실행하기

Java 21을 설치하고 Docker Desktop을 켜세요.
아래 명령은 이 README가 있는 `backend` 폴더의 터미널에서 실행하세요.

처음 한 번만 설정 파일을 만드세요. 이미 `.env`가 있으면 생략하세요.

```bash
cp .env.example .env
```

`.env`의 `JWT_SECRET=` 뒤에 아래 명령 결과를 붙여 넣으세요. 기존 `.env`에 이 줄이 없으면 `.env.example`을 보고 추가하세요.

```bash
openssl rand -base64 32
```

DB와 서버를 실행하세요.

```bash
docker compose up -d --wait
set -a
source .env
set +a
./gradlew bootRun
```

서버 실행 시 필요한 테이블도 자동 생성됩니다.
`.env`는 DB 계정·비밀번호 설정 파일입니다. 위 명령으로 불러오며 Git에는 올라가지 않습니다.

## 실행 확인

[서버 상태 확인](http://localhost:8080/actuator/health)을 열어 **`"status":"UP"`**이 나오면 서버와 DB가 정상입니다.

## 종료하기

서버는 실행 중인 터미널에서 `Ctrl+C`, DB는 아래 명령으로 종료하세요. DB 데이터는 유지됩니다.

```bash
docker compose stop
```

## 테스트하기

```bash
./gradlew test bootJar
```

테스트는 별도 임시 DB(H2)를 사용합니다. 실제 MySQL 연결은 위의 서버 실행으로 확인하세요.

## 개발 서버 배포

[deploy/README.md](deploy/README.md)를 참고하세요.

## 개발 참고

| 항목 | 내용 |
| --- | --- |
| 사용 기술 | Java 21 · Spring Boot 4.1.2 · Gradle · MySQL 8.0 |
| DB 생성 파일 | `src/main/resources/db/migration/` (V1 테이블 생성, V2 자동 증가·중복 방지) |
| 현재 DB | 기존 ERD의 테이블 16개 · 컬럼 143개 · FK 23개 |
| DB 변경 | 적용된 SQL은 수정하지 않고 `V2__설명.sql`처럼 새 파일 추가 |
| ID 생성 | DB 자동 증가(AUTO_INCREMENT). 엔티티는 `@GeneratedValue(strategy = GenerationType.IDENTITY)` |
| DB 포트 변경 | `.env`의 `DB_PORT` 변경 |
| 외부 DB 사용 | `DB_URL` 환경변수 지정 |
| 접속 범위 | 기본적으로 내 컴퓨터에서만 접속 가능 (`127.0.0.1`) |
| 시간 저장 기준 | UTC |

원본 ERD 구조는 유지했습니다. 업무 중복 방지 UNIQUE 8개는 V2에서 추가했습니다.

서버 시작 시 `Validate failed: Migrations have failed validation`이 나오면, 예전 브랜치(`chore/backend-setup`)의 V2 기록이 로컬 DB에 남아 있는 경우입니다. `docker compose down -v`로 로컬 DB를 지우고 다시 실행하세요. DB 데이터가 모두 삭제됩니다.
DB 변경 이력은 Flyway가 관리하며, JPA는 테이블을 자동 수정하지 않습니다.
다운로드 오류 대응을 위해 Google 제공 Maven Central 미러를 우선 사용합니다.
