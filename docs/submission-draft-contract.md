# 제출 임시저장 (KT-18)

`PUT /v1/me/submissions/{requestId}/draft` — SCHOLAR 전용. 토큰의 본인 ID로만 저장한다.

```json
{"answers":{"gpa":3.5},"version":0}
```

- 최초 저장은 version 0, 응답 version은 1이다. 이후에는 직전 응답 version을 보낸다.
- 일반 DRAFT는 answers 전체를 교체한다. 누락·null·빈 문자열 등 미완성 값은 허용한다.
  숫자는 JSON number 또는 null, 체크박스는 boolean 또는 null로 보낸다.
- 입력한 항목만 타입·숫자 min/max·문자열 maxLength·날짜·선택지를 검사한다.
  알 수 없는 키와 FILE 항목은 answers로 저장하지 않는다. 파일은 별도 업로드 API에서 처리한다.
- REVISION_REQUIRED는 최신 미해결 보완 요청의 지정 항목만 변경할 수 있다.
  프론트가 함께 보내는 수정 불가 값은 기존 값과 같으면 허용한다. 생략한 기존 값은 보존하며 상태도 유지한다.
- 공개된 진행 중 요청의 대상자이며 마감 전이어야 한다. 최초 저장이 동시에 들어와도 하나만 생성한다.

200 응답의 `success.data`:

```json
{"submissionId":1,"status":"DRAFT","savedAt":"2026-11-12T08:00:00Z","version":1}
```

| 상태 | 오류 코드 | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | answers/version 누락·잘못된 요청 구조·음수/비정수 version |
| 400 | VALIDATION_ERROR | 입력값 형식 오류. details.fieldErrors는 `[{"key":"gpa","message":"형식을 확인하세요."}]` |
| 401 | UNAUTHORIZED | 미인증 |
| 403 | ACCESS_DENIED / NOT_A_TARGET | 담당자 접근 / 배정되지 않은 장학생 |
| 404 | REQUEST_NOT_FOUND | 요청 없음·미공개 |
| 409 | VERSION_CONFLICT | 이전 version·동시 저장 충돌 |
| 422 | SUBMISSION_LOCKED | 마감·종료·제출 완료·보완 대상 외 변경·미해결 보완 요청 없음 |

저장 시각은 `UtcDateTimes`로 DB에 UTC 초 단위로 저장하고 응답은 같은 시각에 Z를 붙인다.
MySQL은 CAST AS JSON, 테스트용 H2는 FORMAT JSON을 사용해 답변을 JSON 객체로 저장한다.

기준: [임시 저장 명세](https://app.notion.com/p/3e1adc8fd58082d285d401e23cc1f70d),
프론트 SubmissionPage의 전체 답변 전송 및 저장 후 version 갱신 방식.
최종 제출·파일 업로드·자동 저장 타이머는 별도 작업이다.
