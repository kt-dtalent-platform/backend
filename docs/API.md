# MVP API 계약

Base URL: `http://localhost:8080/v1`. 운영은 HTTPS 역방향 프록시 뒤에서 실행한다.
JSON 요청은 `Content-Type: application/json`, 인증은 `Authorization: Bearer {accessToken}`.
파일 업로드는 `multipart/form-data`, 파일 다운로드·엑셀 응답은 바이너리다.
시간은 UTC ISO-8601(`2026-11-30T09:00:00Z`), ID는 서버가 생성한 양의 JSON 안전 정수다.

성공은 HTTP 200, 본문은 아래 형식이다. 삭제·로그아웃·비밀번호 변경의 data는 null이다.

```json
{"resultType":"SUCCESS","success":{"data":{}},"error":null,"meta":{"timestamp":"2026-10-31T00:00:00Z","path":"/v1/users/me"}}
```

실패는 400(입력), 401(인증), 403(권한), 404(대상 없음), 409(상태·버전·중복 충돌), 413(저장 한도), 429(빈도 제한), 500(내부 오류)이다.
SQL·스택·파일 경로·토큰 해시·비밀번호 해시는 응답에 노출하지 않는다.

```json
{"resultType":"FAIL","success":null,"error":{"code":"VERSION_CONFLICT","message":"내용이 변경되었습니다. 새로 조회해 주세요.","details":[]},"meta":{"timestamp":"2026-10-31T00:00:00Z","path":"/v1/submissions/123/confirm"}}
```

## 인증 · 사용자

| 메서드 | 경로 | 요청 · 동작 |
|---|---|---|
| POST | /auth/login | `{email,password}` → accessToken, refreshToken, tokenType, expiresIn |
| POST | /auth/refresh | `{refreshToken}` → 토큰 쌍 재발급, 사용한 refresh 폐기 |
| POST | /auth/logout | 인증 필요, 본인 모든 access/refresh 폐기 |
| GET | /users/me | 본인 ID·이메일·이름·역할·상태 |
| PUT | /users/me/password | `{currentPassword,newPassword}` → 변경 후 모든 토큰 폐기, 재로그인 |

Access 15분, refresh 7일. 토큰은 JWT가 아닌 256비트 랜덤 opaque Bearer 토큰이다.
DB에는 SHA-256 해시만 저장한다. 토큰 서명키를 공유할 필요 없이 매 요청 계정 상태·폐기 여부를 검사한다.
비밀번호는 BCrypt cost 12, 12자 이상·UTF-8 72바이트 이하. 5회 실패하면 15분 잠금.
로그인/갱신은 서버 프로세스별 접속 IP당 합계 분당 20회. 다중 서버에서는 앞단의 공유 rate limiter로 보완한다.
쿠키 인증·HTTP 세션은 사용하지 않는다. 브라우저 refresh token 저장 방식은 FE와 별도 합의해야 한다.

## 프로그램 · 기수 · 장학생

| 메서드 | 경로 | 권한 · 요청 |
|---|---|---|
| GET | /programs | 담당 범위 또는 본인 소속 |
| POST | /programs | 담당자 `{name,description?}` → 생성자에게 새 프로그램 전체 담당 범위 부여 |
| GET | /programs/{id}/cohorts | 담당 범위 또는 본인 기수 |
| POST | /programs/{id}/cohorts | 프로그램 전체 담당자 `{name}` |
| GET | /cohorts/{id}/scholars | 해당 기수 담당자 |
| POST | /scholars | 해당 기수 담당자 `{cohortId,email,name,password,schoolName}` |

회원가입·초대·메일 발송·비밀번호 분실 복구는 제외. 초기 계정은 담당자가 등록하고 안전한 경로로 초기 비밀번호를 전달한다.
담당자 추가·범위 배정 관리 UI/API는 MVP에서 제외하며 운영자가 `manager_programs`에 등록한다.
`cohort_id IS NULL`은 해당 프로그램 전체, 값이 있으면 해당 기수만 접근한다. 범위 없는 담당자는 기존 데이터에 접근할 수 없다.

## 제출 업무

| 메서드 | 경로 | 권한 · 요청 |
|---|---|---|
| GET | /request-templates | 활성 양식 목록 |
| GET | /requests | 본인 대상 또는 담당 범위 업무 |
| POST | /requests | 담당자, 아래 생성 예시 |
| GET | /requests/{id} | 업무 상세 |
| PUT | /requests/{id} | 담당자, DRAFT만 `{version,title,description?,closesAt}` |
| GET | /requests/{id}/fields | 생성 시 복사한 입력항목 |
| POST | /requests/{id}/publish | 담당자 `{version}`, 대상 명단 스냅샷 생성 |
| POST | /requests/{id}/close | 담당자 `{version}`, 진행 업무 마감 |
| GET | /requests/{id}/targets | 담당자, 대상자·제출 상태·미제출 여부. `?notSubmittedOnly=true`로 미작성·작성 중인 대상만 조회 |
| GET | /requests/{id}/submissions | 담당자, 작성된 제출 목록 |
| POST | /requests/{id}/submissions | 대상 장학생, 작성 시작. 이미 있으면 기존 제출 반환 |
| GET | /requests/{id}/my-submission | 장학생 본인 제출, 시작 전 null |
| GET | /requests/{id}/submissions/export.xlsx | 담당자, 대상 명단 XLSX 출력(최대 10,000명). 기본은 전체이며 `?notSubmittedOnly=true`는 같은 미제출 필터를 적용하고 임시 성적을 제외 |

```json
{"programId":20,"cohortId":30,"title":"2026-1 성적증명서 제출","description":"성적과 증명서를 확인하세요.","academicYear":2026,"semester":"1","closesAt":"2026-11-30T09:00:00Z","completionMessage":"제출되었습니다."}
```

MVP는 GRADE_CERTIFICATE 고정 양식이다. cohortId 생략/null은 프로그램 전체이며 프로그램 전체 담당 권한이 필요하다.
semester는 `1`, `2`, `SUMMER`, `WINTER`. 공개 시 ENROLLED이며 삭제되지 않은 계정 전원을 명단에 고정한다.
계정 잠금·일시 비활성 때문에 대상 명단에서 장학생을 누락하지 않는다. 공개 이후 신규 등록자는 자동 추가되지 않는다.
대상이 없거나 마감일이 지난 업무는 공개할 수 없다. 공개 후 양식과 업무 내용은 수정하지 않는다.
미제출 집계는 제출 행 없음 또는 DRAFT. 행 없음은 조회 전용 `NOT_STARTED`로 표시하며 DB ENUM에는 추가하지 않는다.

## 제출 · 검토 · 파일

| 메서드 | 경로 | 요청 · 동작 |
|---|---|---|
| GET | /submissions/{id} | 본인 또는 담당자 상세 |
| PUT | /submissions/{id}/answers | 장학생 `{version,answers:{gpa,gpa_scale,credits}}`, 전체 answers 교체 |
| POST | /submissions/{id}/submit | 장학생 `{version,noticeAcknowledged,manualReviewRequested,manualReviewReason?}` |
| POST | /submissions/{id}/confirm | 담당자 `{version}` |
| POST | /submissions/{id}/revision-requests | 담당자 `{version,reason,fields:["credits","certificate"]}` |
| GET | /submissions/{id}/revision-requests | 보완 사유·대상 항목·해결 시각 |
| GET | /submissions/{id}/histories | 상태·변경 전 입력값·파일 메타데이터 이력 |
| GET | /submissions/{id}/files | 현재/교체된 파일 메타데이터 |
| POST | /submissions/{id}/files?version={n} | multipart `file` 파트, 증명서 업로드·교체 |
| DELETE | /files/{id}?version={n} | 미제출 파일 삭제 |
| GET | /files/{id}/download | 인증·범위 검사 후 attachment 다운로드, 감사 이력 |

장학생은 DRAFT 또는 REVISION_REQUIRED에서만 수정한다. 제출·검토·업로드·삭제는 version을 검사한다.
파일 업로드·삭제·답변 변경·상태 변경 후 version이 증가한다. 상세 재조회로 최신 version을 얻는다.
보완 중에는 요청된 항목만 변경할 수 있다. 보완 항목이 certificate가 아니면 파일 교체도 거절한다.
최종 제출에는 필수 성적·활성 파일·안내 확인이 필요하며, 유효한 MATCH가 없으면 사유가 있는 수동 확인 요청이 필요하다.
성적은 숫자, 소수 최대 2자리. 만점은 4.0/4.3/4.5, 평점은 0~만점, 학점은 0~100. 다른 학교 기준이 필요하면 정책과 검증을 함께 변경한다.

상태: DRAFT → SUBMITTED → CONFIRMED 또는 REVISION_REQUIRED → RESUBMITTED → 재검토.
마감시각 이후 신규 작성·최초 제출·DRAFT 수정·분석·파일 변경은 거절한다. 담당자 검토는 마감 후에도 가능하다.
예외로 담당자가 항목을 지정해 보완 요청하면 해당 제출의 수정·분석·파일 변경·재제출은 마감 후에도 허용한다.
기존 마감 후 재제출은 isLate=true로 기록한다. 일반 장학생의 임의 지각 제출를 허용하는 것은 아니다.

파일은 PDF/PNG/JPEG, 최대 10MiB. 확장자·바이트 시그니처를 검사하고 클라이언트 MIME은 신뢰하지 않는다.
반복 업로드를 통한 저장소 고갈을 줄이기 위해 제출당 누적 최대 20개·50MiB로 제한한다. 삭제·교체된 파일도 이 한도에 포함한다.
저장명은 UUID이며 원본명을 경로로 사용하지 않는다. 비공개 로컬 저장소에 보관하고 정적 URL을 제공하지 않는다.
교체된 파일은 supersededAt으로 구분하고 제출 이력을 위해 보존한다. 한 번 제출된 자료의 직접 삭제는 금지한다.
실행 서버 OS 계정·저장소 접근 권한·디스크 암호화·백업 암호화와 악성코드 검사는 배포 환경에서 별도 구성해야 한다.

## 문서 분석

| 메서드 | 경로 | 요청 · 동작 |
|---|---|---|
| POST | /submissions/{id}/analyses | 장학생 `{fileId,version,noticeAcknowledged:true}` |
| GET | /submissions/{id}/analyses | 분석 회차 목록 |
| GET | /analyses/{id} | 상태·추출값·비교·무효화 시각 |

파일당 최대 5회. 매 분석마다 입력값 스냅샷을 저장한다. 답변·파일 변경 시 이전 결과를 무효화한다.
현재는 최대 25초 동기 외부 호출이며 그동안 해당 제출 행이 잠긴다. 대량 처리 시 작업 큐·비동기 처리로 전환해야 한다.
OCR_URL 미설정 시 HTTP 200으로 저장된 분석 작업을 반환하되 상태는 FAILED, failureReason은 OCR_NOT_CONFIGURED다.
외부 장애는 OCR_UNAVAILABLE로 저장하고 수동 검토로 제출할 수 있다. 실패를 MATCH로 대체하지 않는다.

운영자가 허용한 고정 HTTPS OCR_URL만 호출한다. 자동 리다이렉트를 따라가지 않는다.
`Authorization: Bearer {OCR_API_KEY}`, 아래 JSON을 POST한다. 학생 입력값은 OCR에 보내지 않는다.

```json
{"contentType":"application/pdf","fileBase64":"..."}
```

분석기 응답은 아래 형식의 JSON이다. 판정은 서버에서 수행하므로 외부 overallResult는 사용하지 않는다.

```json
{"school":"테스트대학교","documentType":"GRADE_CERTIFICATE","academicYear":2026,"semester":"1","gpa":4.1,"gpa_scale":4.5,"credits":18,"hasF":false}
```

읽지 못한 값은 생략/null로 반환해야 한다. 숫자는 JSON number로 반환한다.
F가 감지되거나 F 여부가 불명이면 UNREADABLE(수동 확인), 등록된 학교·대상 기간·입력 성적과 다르면 MISMATCH다.
학교명은 공백·대소문자만 정규화하며 별칭이 다르면 담당자가 확인한다.
학교·문서 종류·기간·성적·F 여부가 모두 확인되어야 MATCH가 가능하다. MATCH도 최종 승인을 의미하지 않는다.
실제 OCR 공급자 설정·개인정보 처리 조건 확인·학교별 인식률 측정은 외부 연동 작업이다.

## 현재 운영 전 확인 사항

프로그램·기수·장학생·업무·대상자·제출 목록은 `?after=0&size=50` 커서 페이지네이션을 지원한다.
size는 1~200, 다음 페이지는 응답 `meta.nextCursor`를 after에 전달한다. `meta.hasMore=false`이면 끝이다.
정렬은 ID 오름차순이며 새 데이터가 추가되면 처음부터 다시 조회한다. 엑셀은 페이지와 별도로 최대 10,000건이다.
현재 원본 1년 자동 파기는 구현하지 않았다. 보관 기산점, 삭제 대상(원본·OCR·스냅샷·백업)을 재단과 확정한 뒤 파기 작업을 추가해야 한다.
법적 동의·개인정보 처리방침 버전 관리는 noticeAcknowledged와 별개다. 안내 확인 시각만 기록한다.
실제 개인정보를 사용하기 전 HTTPS, 저장소/백업 암호화, 외부 OCR 제공자, 비밀번호 전달 방식, 파기 정책, 취약점 검증을 완료해야 한다.
코드와 테스트가 있다는 사실만으로 보안 인증이나 실사용 승인을 받은 것은 아니다.
