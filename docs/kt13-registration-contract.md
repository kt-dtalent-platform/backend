# KT-13 등록 계약 검토 (2026-11-12)

## 반영한 확정 사항

- PENDING_INVITE 저장 및 초대 흐름 없음.
- 생년월일 요청 필드 제거. university는 @NotBlank 필수.
- MANAGER는 모든 기수 등록 가능.
- DB 자동 ID, 삭제되지 않은 계정 이메일 전역 유일성, 삭제 계정 이메일 재사용.
- 계정과 프로필은 Service 트랜잭션으로 함께 저장.
- 임시 비밀번호는 무작위 생성하고 BCrypt 해시만 DB 저장. password_change_required=true.
- 자동 메일, QR 인증, 본인 확인, 담당 기수 제한은 추가하지 않음.

## 리뷰 전 미확정 사항

1. temporaryPassword 응답은 담당자에게 한 번 제공하기 위한 **구현 제안**이며 승완 답변에서 확정된 계약이 아니다. 전달 방식과 응답 필드 합의 전 Draft 유지.
2. accountStatus=ACTIVE는 기존 로그인에서 INACTIVE를 차단하므로 임시 비밀번호 로그인을 가능하게 하기 위한 선택이다. 별도 초대 대기 상태는 없다.
3. 없는 기수는 404 COHORT_NOT_FOUND로 응답하도록 구현했으나, 추가 오류 계약으로 리뷰 필요.
4. 입력 검증 오류는 기존 공통 처리의 400 INVALID_REQUEST를 그대로 쓴다. 예전 명세의 VALIDATION_ERROR와 차이를 남긴다.
5. 비밀번호 변경 API와 로그인 passwordChangeRequired 응답은 승완 PR #45에 있다. 이 PR에 복제하지 않는다.

## 기획 근거

- 11/11 승완 답변: 초대 상태 제외, 생년월일 제외, 학교 필수, 전체 기수 등록.
- Figma 정책: https://www.figma.com/design/17p2oVUeNOuQuDk2xPrRzM?node-id=649-3521
- Figma 로그인: https://www.figma.com/design/17p2oVUeNOuQuDk2xPrRzM?node-id=367-22
- 11/8 회의: https://app.notion.com/p/40fadc8fd580802cba5dd0f977390226

공통 QR은 사이트 진입 링크로 해석할 수 있으나 QR 자체에 대한 명시적 결정은 찾지 못했다. 기획의 확실한 부분은 안내받은 이메일·비밀번호 로그인 후 첫 로그인 비밀번호 변경이다.
