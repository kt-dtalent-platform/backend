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

## 개발 참고

| 항목 | 내용 |
| --- | --- |
| 사용 기술 | Java 21 · Spring Boot 4.1.2 · Gradle · MySQL 8.0 |
| DB 생성 파일 | `src/main/resources/db/migration/V1__create_initial_schema.sql` |
| 현재 DB | 기존 ERD의 테이블 16개 · 컬럼 143개 · FK 23개 |
| DB 변경 | 적용된 SQL은 수정하지 않고 `V2__설명.sql`처럼 새 파일 추가 |
| ID 생성 | 자동 증가 없음. 엔티티 작성 전 생성 방식 확정 필요 |
| DB 포트 변경 | `.env`의 `DB_PORT` 변경 |
| 외부 DB 사용 | `DB_URL` 환경변수 지정 |
| 접속 범위 | 기본적으로 내 컴퓨터에서만 접속 가능 (`127.0.0.1`) |
| 시간 저장 기준 | UTC |

원본 ERD 구조는 유지했습니다. 기본키 외 UNIQUE와 추가 삭제 정책은 넣지 않았습니다.
DB 변경 이력은 Flyway가 관리하며, JPA는 테이블을 자동 수정하지 않습니다.
다운로드 오류 대응을 위해 Google 제공 Maven Central 미러를 우선 사용합니다.
