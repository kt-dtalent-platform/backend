# 개별 연장 마감일

## API

담당자 GET/PATCH /v1/requests/{requestId}/targets/{scholarId}/deadline
PATCH body: {"dueAt":"2026-12-01T14:59:59Z"}
응답: requestId, scholarId, commonDueAt, dueAt.

- 공개된 IN_PROGRESS 요청의 대상자에게만 연장한다.
- 공통 기한 경과 후에도 연장할 수 있지만 명시적으로 CLOSED인 요청은 거절한다.
- 현재 유효 마감일보다 뒤인 미래 시각만 허용한다. 단축·취소 API는 추가하지 않았다.
- 다른 장학생의 마감일과 요청 공통 closesAt은 바꾸지 않는다.
- 내 할 일 dueAt·D-day·마감순 정렬, 제출 폼 requestDueAt, 임시저장·최종 제출·보완 재제출에 같은 값을 적용한다.
- 마감 시각 이상에서는 제출을 막는다. DB 경계는 UtcDateTimes로 변환한다.

## 파일 API 의존성

현재 main에는 S3 PR #53·#62·#59가 아직 머지되지 않아 FilePresignRepository가 없다.
이 브랜치에 미머지 S3 구현 전체를 복제하지 않았다.
file-personal-deadline.patch는 선행 S3 코드의 공통 requestState 조회에 개별 마감일을 적용하는 최소 변경이다.
S3 PR과 이 브랜치가 main에 모인 후 적용해야 업로드 URL 발급·업로드 완료·파일 삭제까지 동일한 기한을 사용한다.
이 패치는 로컬 통합 검증 브랜치에서 적용·검증했다.
file-personal-deadline-test.patch는 연장 대상자·비대상자·기한 경과의 업로드/완료/삭제 회귀 테스트다. 선행 코드가 모인 후 두 패치를 함께 검토·적용한다. 적용 전에는 파일 API 연장이 운영에 반영됐다고 표시하지 않는다.

## migration 순서

V4 학교 → V5 계좌 → V6 개별 연장 순서의 적층 브랜치다.
적용된 migration은 수정하지 않는다.
