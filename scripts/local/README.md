# 로컬 연동 테스트 데이터

기존 스키마를 사용하는 가상 데이터 6행입니다. 서버 시작 시 자동 적용하지 않고 아래 명령으로 직접 입력합니다.

| 항목 | 값 |
| --- | --- |
| 담당자 | admin@kt.com / LocalDemo123! |
| 장학생 | hong@kt.com / LocalDemo123! |
| 프로그램 ID | 900001 |
| 기수 ID | 900001 |
| 사용자 정의 템플릿 ID | 900001 |

두 계정은 ACTIVE입니다. 비밀번호는 공통 설정과 같은 BCryptPasswordEncoder로 해시했습니다. 템플릿 fields는 빈 배열이며 실제 성적 제출 시나리오는 포함하지 않습니다.

데이터가 없는 로컬 개발 DB에서 한 번만 실행합니다. 운영·공용 DB에 적용하지 않습니다. 스키마는 서버의 Flyway 실행으로 먼저 생성해야 합니다.

backend 디렉터리에서 실행할 명령:

```bash
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/local/seed.sql
```

기존 행을 수정하거나 삭제하지 않습니다. 중복 실행 시 기본키 오류가 발생합니다. 위 배치 명령을 사용하고 --force 옵션을 추가하지 않습니다.

## 검증 결과 (2026-11-09)

격리된 MySQL 8.0.46과 인증 후속 코드로 확인했습니다.

- Flyway 스키마 생성 후 데이터 6행 입력, 한글 및 관계 확인
- 두 계정의 실제 로그인 200 및 역할 확인
- JWT 유효 시간 7,200초 확인
- MANAGER `/v1/me`: 프로그램 900001의 id·name 반환
- SCHOLAR `/v1/me`: accessiblePrograms는 null
- 토큰 없음: 401 UNAUTHORIZED, 장학생의 담당자 경로 접근: 403 ACCESS_DENIED
- Swagger UI 및 OpenAPI 문서 HTTP 200, 프로그램 목록 배열 스키마 확인
- 중복 입력은 오류로 중단되고 기존 데이터 유지

기수·템플릿 조회 API와 실제 프론트 화면 연결은 별도 기능 브랜치에서 확인해야 합니다. 위 결과는 Swagger UI에서 수동으로 버튼을 누른 결과가 아니라 실제 HTTP 호출로 확인한 결과입니다.
