# 내 제출 폼 조회 계약 (KT-17)

GET /v1/me/submissions/{requestId}, SCHOLAR 전용. 본인 ID는 토큰에서만 가져온다.

## 적용 근거

- [명세와 프론트 담당자 11/5 댓글](https://app.notion.com/p/280adc8fd5808216895601ff4a2d8572): options·helpText 요청, editableFieldKeys는 프론트 계산 가능, 최초 임시 저장 version 0.
- [현재 프론트 RawSubmissionForm·toForm](https://github.com/kt-dtalent-platform/frontend/blob/8f7ddab176565bcf89f649aeb70af409d2828088/src/api/submissions.ts): 기존 중첩 응답 사용.
- 11/8 회의 및 일별 계획: 개인정보 안내는 화면에서 제외.
- 11/9 공통 규칙: 시각은 UTC Instant(Z), 단순 조회 외 판단·조합은 Service.

## 응답

공통 success.data 안에 request, fields, submission, files, revision을 반환한다. 예전 명세의 평탄한 submissionId·status·answers·version 응답으로 바꾸지 않는다.

| 필드 | 계약 |
| --- | --- |
| request | id, title, description(null 가능), requestDueAt(UTC Z), status(IN_PROGRESS/CLOSED) |
| fields | request_fields 스냅샷을 sort_order 순으로 반환. fieldKey, label, fieldType, required, options, validation, helpText |
| fields[].options | [{value, label}] 또는 null. SELECT_ONE/SELECT_MULTI의 선택지 |
| fields[].helpText | 문자열 또는 null |
| submission | 제출 행이 있으면 id, status, answers, version. 최초 진입은 null |
| files | 본인 제출의 id, fieldKey, originalName, uploadStatus. 프론트는 UPLOADED만 표시 |
| revision | REVISION_REQUIRED일 때 최신 미해결 보완 요청의 id, reason, fieldKeys. 없으면 null |

GET은 최초 초안을 생성하지 않는다. 프론트 toForm은 submission=null을 submissionId=null, status=NOT_SUBMITTED, answers={}, version=0으로 해석하고 첫 임시 저장에 version 0을 보낸다.

## 수정 가능 항목

프론트가 현재 구현한 계산을 유지한다. 백엔드가 editableFieldKeys를 중복 제공하지 않는다.

- 요청 CLOSED 또는 제출 SUBMITTED/RESUBMITTED/CONFIRMED: 수정 가능 항목 없음.
- REVISION_REQUIRED: revision.fieldKeys만 수정 가능. 미해결 보완이 없으면 빈 목록.
- 최초 진입 및 DRAFT: 폼의 전체 fieldKey.

서버의 실제 쓰기 권한 검증은 후속 임시 저장·제출 API에서 별도로 수행해야 한다. 조회 응답은 쓰기 권한 검증을 대체하지 않는다.

## MVP 제외 및 비공개 값

privacyNotice, 별도 revision.dueAt은 제공하지 않는다. DB에 별도 보완 기한 칸이 없으므로 임의 값을 만들거나 마이그레이션을 추가하지 않는다. 프론트는 request.requestDueAt을 사용한다. internalMemo·manual_review_reason·s3_key는 응답하지 않는다.

## 오류

- 400 INVALID_REQUEST: 올바르지 않은 요청 ID
- 401 UNAUTHORIZED: 인증 없음
- 403 ACCESS_DENIED: SCHOLAR가 아닌 역할
- 403 NOT_A_TARGET: 공개 요청의 본인 대상 아님
- 404 REQUEST_NOT_FOUND: 요청 없음·공개 전·published_at 없음

## 프론트 연동 참고

현재 프론트 FormField.options 타입은 string[]로 남아 있지만, 11/5 요청에 따른 실제 응답은 {value,label}[]다. 프론트 연동 때 해당 타입과 선택지 렌더링을 맞춰야 한다. 이 작업에서는 프론트 저장소를 수정하지 않는다.
