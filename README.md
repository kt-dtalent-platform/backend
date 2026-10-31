# KT 장학 플랫폼 백엔드

Java 21 · Spring Boot 4.1.2 · Gradle 9.8.2 · MySQL 8.0 · Flyway.

성적증명서 MVP API 38개: 인증·토큰 갱신·비밀번호 변경, 프로그램·기수·사전 계정 등록,
업무 생성·공개·대상 명단, 제출·업로드·검토·보완·재제출·변경 이력, XLSX 출력, OCR 요청·결과·비교.

**[API 계약·요청 예시·권한·제약](docs/API.md)**

[검증 결과: H2·실제 MySQL 각각 22개 테스트 통과](docs/VERIFICATION.md)

상용 운영 판정: 보류. [상용 준비도 점검](docs/READINESS_AUDIT.md)

## 실행

Java 21과 Docker Desktop이 필요합니다. 이 README가 있는 폴더에서 실행하세요.
처음 한 번 `.env.example`을 `.env`로 복사하고 DB 비밀번호를 변경하세요.
새 DB에서만 `BOOTSTRAP_MANAGER_EMAIL`, `BOOTSTRAP_MANAGER_PASSWORD`를 설정하면 초기 담당자가 생성됩니다.
초기화 후 두 값을 지우세요. 기존 계정은 덮어쓰지 않습니다. 비밀번호는 12자 이상, UTF-8 72바이트 이하입니다.

Windows PowerShell:

```powershell
Copy-Item .env.example .env   # 이미 .env가 있으면 생략
# .env를 편집한 뒤
.\scripts\start.ps1
```

외부 MySQL을 쓰면 `.env`에 `DB_URL`을 설정하고 `start.ps1 -SkipDatabase`로 실행하세요.
스크립트는 `.env`를 실행하지 않고 KEY=VALUE로 읽습니다. 따옴표 없이 값을 입력하세요.

macOS/Linux:

```bash
cp .env.example .env  # 이미 있으면 생략
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun
```

`.env`는 Git 추적에서 제외됩니다. 비밀번호·토큰을 화면 공유나 로그에 노출하지 마세요.

[서버·DB 상태](http://localhost:8080/actuator/health)는 인증 없이 status: UP만 반환합니다.
API 주소는 `http://localhost:8080/v1`입니다. 로그인 외 업무 API에는 Bearer 인증이 필요합니다.
서버 종료는 Ctrl+C, DB 종료는 `docker compose stop`이며 DB 데이터는 유지됩니다.

## 처음 사용하는 순서

1. 초기 담당자로 POST /v1/auth/login을 호출합니다.
2. Bearer 토큰으로 프로그램 → 기수 → 장학생 계정을 생성합니다.
3. 성적증명서 업무를 생성하고 version 0으로 공개합니다.
4. 장학생으로 로그인해 작성 시작 → 답변 저장 → 파일 업로드 → 문서 분석 → 최종 제출합니다.
5. 담당자가 확인 완료 또는 항목별 보완 요청을 합니다. 장학생은 요청된 항목만 고치고 재제출합니다.
6. 담당자는 대상 명단, 미제출 여부, 이력과 엑셀을 확인합니다.

모든 수정은 최신 version을 보내야 합니다. 최초 생성은 0, 변경마다 1 증가합니다.
OCR 공급자가 없으면 분석은 명시적으로 FAILED가 됩니다. 사유와 함께 수동 확인을 요청하면 제출할 수 있습니다.
실제 OCR 공급자 연결·인식률은 구현된 API와 별개이며 아직 검증하지 않았습니다.

## 테스트

```powershell
.\gradlew.bat clean test bootJar
```

기본 테스트는 H2 임시 DB와 실제 임의 포트 HTTP 서버를 사용합니다. MySQL 서비스가 필요하지 않습니다.
인증·회전 토큰·잠금·권한, 대상 스냅샷, 제출·보완·재제출·확인, 동시 수정 충돌,
파일 형식·소유권·이력·XLSX·분석 실패·F/인식 불명 비교를 검증합니다.
이 테스트만으로 실제 MySQL 호환성과 운영 보안을 인증하지는 않습니다.
OneDrive 파일 잠금이나 보고서 오류 발생 시 동기화 외 로컬 폴더에서 빌드하세요.

## DB 변경과 구현 선택

- V1(기존 ERD)은 수정하지 않았습니다. V2는 UNIQUE·조회 인덱스, manager_programs, access_tokens, 파일 교체 시각을 추가합니다.
- V3는 GRADE_CERTIFICATE 양식을 등록합니다. 기존 양식 ID/코드나 중복 데이터가 있으면 정리 후 마이그레이션해야 합니다.
- DB 변경은 Flyway로 관리합니다. API는 JDBC와 명시적 트랜잭션을 사용하며 미사용 JPA 의존성은 제외했습니다.
- ID는 SecureRandom으로 생성하는 양의 53비트 범위 정수입니다. PK 제약으로 충돌을 거절하며 기존 ID는 변경하지 않습니다.
- Access/refresh는 원문을 DB에 저장하지 않는 opaque 토큰입니다. 인증 시 계정 상태·만료·폐기를 확인합니다.
- 변경은 행 잠금과 클라이언트 version 검사로 처리합니다. 대상 명단은 공개 시점에 고정합니다.
- 파일은 UUID 저장명으로 비공개 디렉터리에 보관하며 인증된 다운로드에서만 제공합니다.
- 시간 기준은 UTC이며 API 시간은 Z가 붙은 ISO-8601입니다.

## 운영 전 남은 사항

실제 OCR 엔진·재단 저장 방식 승인·보관 기산점과 1년 자동 파기(백업 포함)는 아직 연결/확정되지 않았습니다.
자동 파기를 구현한 상태로 간주하지 마세요. 현재 제출된 파일은 이력을 위해 보존합니다.
실제 개인정보로 운영하기 전 HTTPS, 저장소/백업 암호화와 접근 권한, 악성 파일 검사, 파기 정책을 구성하고 검증해야 합니다.
개발 기본 저장 경로는 OneDrive 프로젝트 내부일 수 있으므로 실제 자료는 승인된 비공개 비동기화 경로로 바꾸세요.
목록은 커서 페이지네이션(페이지당 최대 200건), 엑셀은 최대 10,000건입니다. 대규모 운영에는 비동기 OCR 작업 큐가 필요합니다.

Maven Central 다운로드 오류 대응으로 Google 제공 미러를 우선 사용합니다.
로컬 검증 도구는 .tools에 저장되며 Git 추적에서 제외됩니다. 일반 개발자는 설치된 Java 21로 wrapper를 사용하면 됩니다.
PowerShell 시작 스크립트는 시스템 Java가 없으면 .tools의 검증용 Java도 자동으로 찾습니다.
