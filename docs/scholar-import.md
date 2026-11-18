# 장학생 엑셀 일괄 등록

담당자 전용. 기존 개별 등록의 학교 검증·이메일 중복·임시 비밀번호·첫 로그인 변경 규칙을 그대로 사용한다.

## 현재 프론트 연동 계약
- 프론트가 엑셀을 읽고 `POST /v1/scholars/bulk`로 JSON `{cohortId, scholars:[{name,email,university,phone,major,studentNo,schoolId?}]}`을 보낸다.
- 1~500명. 학교ID는 선택이며 생략하면 직접 입력 학교명으로 저장한다.
- 성공 `201`: `{created, items:[{row,id,email,temporaryPassword}]}`. 현재 프론트는 `created`를 읽는다.
- 오류가 한 건이라도 있으면 전체 취소한다. 부분 성공은 지원하지 않는다.
- `409 DUPLICATE_EMAIL`의 details는 프론트가 이메일 오류를 표시할 수 있는 문자열 배열이다. 첫 중복에서 중단하며 전체 명단의 중복 목록을 보장하지 않는다.
- 그 외 행 검증 오류는 `[{row,field,message}]`. row는 전송된 명단의 순서 + 1이며, 프론트가 빈 행을 제거하므로 원본 엑셀의 실제 행 번호와 다를 수 있다.
- 프론트 양식 다운로드·엑셀 파싱을 그대로 사용한다. 기존 파일 업로드 경로는 별도 사용 가능하며 한 트랜잭션의 등록 로직을 공유한다.

## API
- `GET /v1/scholars/import/template`: `.xlsx` 입력 양식 다운로드.
- `POST /v1/scholars/import`: `multipart/form-data`의 `file`과 `cohortId`(form 또는 query).
- 파일은 시트 하나인 `.xlsx`, 최대 1MB. 헤더 다음 2~201행에 최대 200명 입력. 빈 행은 건너뛴다.
- 열 순서: **이름 / 이메일 / 학교 / 연락처 / 전공 / 학번 / 학교ID**.
- 이름·이메일·학교 필수. 연락처·전공·학번은 선택이며 문자 형식으로 입력한다. 수식은 받지 않는다.
- 학교ID는 선택한 목록 ID(정수) 또는 빈칸. 있으면 서버가 학교 이름을 확인하고, 빈칸이면 직접 입력 학교명으로 저장한다.
- 템플릿은 학번·연락처 등의 앞자리 0을 보존하도록 문자 열 형식을 지정한다. 이미 숫자로 저장되어 사라진 0은 복구하지 않는다.

## 응답
- 성공 `201`: `{created, items:[{row,id,email,temporaryPassword}]}`. 응답은 `Cache-Control: no-store`.
- 임시 비밀번호는 이 응답에서 한 번만 제공하므로 담당자가 별도로 안내한다. 이메일 발송·QR 로그인은 추가하지 않는다.
- 입력·파일 오류 `400 INVALID_REQUEST`, 없는 기수 `404 COHORT_NOT_FOUND`, 이메일 중복 `409 DUPLICATE_EMAIL`.
- 서버 업로드 크기 제한 초과 `413 INVALID_REQUEST`.
- 실패 details: `[{row,field,message}]`. row는 엑셀 행 번호이며, 파일 전체 오류는 0, 헤더 오류는 1.
- 한 행이라도 실패하면 이 요청에서 저장한 계정·프로필을 모두 취소한다. 기존 계정을 덮어쓰지 않는다.
- 파일 내부 이메일 중복은 대소문자를 구분하지 않는다. DB 중복은 기존 UNIQUE 제약으로 동시 요청까지 검사한다. 탈퇴한 계정의 이메일 재사용은 기존 규칙을 유지한다.

## 프론트 연결
GitHub main의 `useBulkCreateScholars`와 `ScholarImportPage` 기준으로 JSON 요청·created 응답·중복 이메일 details를 맞췄다. 프론트 소스·인증 코드는 수정하지 않았다. 현재 프론트는 성공 응답의 임시 비밀번호를 표시하지 않고 명단으로 이동하므로 담당자 전달용 화면은 별도 연결이 필요하다.

## 의존성
#83(feat/KT-54)의 학교 ID 저장 위에 쌓인 PR이다. 새 migration·외부 서비스·비동기 작업은 추가하지 않는다.
