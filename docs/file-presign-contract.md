# 업로드 URL 발급 준비 (KT-19)

기준: [업로드 URL 발급 명세](https://app.notion.com/p/3fcadc8fd580828781a98140374b03b8),
11/9 팀 결정, 이슈 #30.

- `POST /v1/submissions/{submissionId}/files/presign`, 장학생 본인 제출에만 허용.
- 요청: fieldKey, fileName, contentType, size(byte). 기존 DTO는 문자열 길이·필수값·양의 정수 크기를 검증한다.
- request_fields.validation의 allowedExtensions(소문자·점 없음), maxSizeMb(파일 1개 MB 정수), maxFiles(항목당 개수) 사용.
- 본인 여부·마감·수정 가능한 제출 상태·FILE 항목·보완 지정 항목 판단은 Service에서 처리한다.
- 보완 중에는 requested_at DESC, id DESC의 최신 미해결 보완 요청 1건만 기준으로 삼는다.
- 성공 시 PENDING 파일 행을 생성하고 fileId·uploadUrl·expiresAt을 반환한다. PUT URL 유효기간은 10분이다.
- 확장자 오류 400 INVALID_EXTENSION, 개수 초과 400 FILE_COUNT_EXCEEDED, 용량 초과 413 FILE_TOO_LARGE.
- 시간은 팀 공통 규칙대로 UTC 저장·응답 Z. 명세의 IMAGE 타입은 현 DB에 없으므로 FILE을 사용한다.

현재 구현된 것은 요청 검증·사전 조회다. HTTP API·PENDING 저장·S3 서명은 아직 구현하지 않았다.

다음 연결 전 확인할 사항:

- 실제 S3 버킷 이름·리전과 로컬/서버 인증 방식. 콘솔 로그인 계정은 애플리케이션용 자격 증명이 아니다.
- 버킷 CORS의 실제 프론트 Origin, PUT 및 필요한 헤더. 임의의 전체 Origin을 허용하지 않는다.
- 누락된 validation 제한값 처리 정책, 만료된 PENDING 파일 처리 방식.
- 파일 개수 검사·PENDING 생성은 동일 제출에서 동시 발급해도 제한을 넘지 않게 처리한다.
- 업로드 완료 API에서 실제 객체의 크기·타입을 재검증한다 (11/17 후속 작업).
