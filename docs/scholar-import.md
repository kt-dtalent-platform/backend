# 장학생 엑셀 일괄 등록

담당자 전용. 기존 개별 등록의 학교 검증·이메일 중복·임시 비밀번호·첫 로그인 변경 규칙을 그대로 사용한다.

## 현재 프론트 연동 계약
- 프론트가 엑셀을 읽고 `POST /v1/scholars/bulk`로 JSON `{cohortId, scholars:[{name,email,university,phone,major,studentNo,schoolId?}]}`을 보낸다.
- 1~500명. 학교ID는 선택이며 생략하면 직접 입력 학교명으로 저장한다.
- 성공 `201`: `{created, items:[{row,id,email,temporaryPassword}]}`. 현재 프론트는 `created`를 읽는다.
- 오류가 한 건이라도 있으면 전체 취소한다. 부분 성공은 지원하지 않는다.
- `409 DUPLICATE_EMAIL`의 details는 프론트가 이메일 오류를 표시할 수 있는 문자열 배열이다. 첫 중복에서 중단하며 전체 명단의 중복 목록을 보장하지 않는다.
- 그 외 행 검증 오류는 `[{row,field,message}]`. row는 전송된 명단의 순서 + 1이며, 프론트가 빈 행을 제거하므로 원본 엑셀의 실제 행 번호와 다를 수 있다.
- 프론트 양식 다운로드·엑셀 파싱을 그대로 사용한다. 백엔드는 JSON 등록만 제공한다.

## 응답과 저장 규칙
- 성공 응답은 `Cache-Control: no-store`. 임시 비밀번호는 응답에서 한 번만 제공한다.
- 입력 오류 `400 INVALID_REQUEST`, 없는 기수 `404 COHORT_NOT_FOUND`, 이메일 중복 `409 DUPLICATE_EMAIL`.
- 한 행이라도 실패하면 계정·프로필을 모두 취소한다. 기존 계정을 덮어쓰지 않는다.
- 이메일 중복은 대소문자를 구분하지 않는다. 동시 요청은 DB UNIQUE 제약으로 방지한다.
- 탈퇴 계정의 이메일은 재사용할 수 있다.
- 파일 파싱과 양식 다운로드는 프론트에서 처리한다. `/import`와 `/import/template`는 제공하지 않는다.

## 프론트 연결
GitHub main의 `useBulkCreateScholars`와 `ScholarImportPage` 기준으로 JSON 요청·created 응답·중복 이메일 details를 맞췄다. 프론트 소스·인증 코드는 수정하지 않았다. 현재 프론트는 성공 응답의 임시 비밀번호를 표시하지 않고 명단으로 이동하므로 담당자 전달용 화면은 별도 연결이 필요하다.

## 의존성
#83(feat/KT-54)의 학교 ID 저장 위에 쌓인 PR이다. 새 migration·외부 서비스·비동기 작업은 추가하지 않는다.
