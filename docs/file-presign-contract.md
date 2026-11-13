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

HTTP API·사전 검증·PENDING 저장·AWS SDK PUT 서명을 구현했다.
로컬 자동 테스트는 실제 계정과 무관한 가상 자격 증명으로 오프라인 서명을 검증한다.
실제 버킷 PUT·CORS·업로드 완료 단계는 아직 검증하지 않았다.

- S3_ENABLED 기본값은 false다. 버킷·리전·SDK 자격 증명이 준비되지 않으면 URL을 발급하지 않는다(503 STORAGE_UNAVAILABLE).
- SDK 기본 자격 증명 체인(로컬 프로필/환경변수, 서버 IAM 역할)을 사용한다. 콘솔 비밀번호는 사용하지 않는다.
- allowedExtensions·maxSizeMb·maxFiles가 빠졌거나 잘못된 항목은 400 INVALID_REQUEST로 거절한다. 임의 기본값은 적용하지 않는다.
- PENDING과 UPLOADED를 개수에 포함하고 DELETED는 제외한다. 만료된 PENDING 자동 정리는 아직 하지 않는다.
- 서명 실패는 PENDING 생성을 롤백한다. 응답은 Cache-Control: no-store이며 로그용 toString에서 URL을 가린다.

다음 연결 전 확인할 사항:

- 실제 S3 버킷 이름·리전과 로컬/서버 인증 방식. 콘솔 로그인 계정은 애플리케이션용 자격 증명이 아니다.
- 버킷 CORS의 실제 프론트 Origin, PUT 및 필요한 헤더. 임의의 전체 Origin을 허용하지 않는다.
- 누락된 validation 제한값 처리 정책, 만료된 PENDING 파일 처리 방식.
- 파일 개수 검사·PENDING 생성은 동일 제출에서 동시 발급해도 제한을 넘지 않게 처리한다.
- 업로드 완료 API에서 실제 객체의 크기·타입을 재검증한다 (11/17 후속 작업).

SDK 참고: [PUT URL 서명](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html),
[Gradle BOM 의존성](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-gradle.html).
