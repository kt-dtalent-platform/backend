# 로그인 안내 이메일 발송

## 담당자 흐름
1. 기존 단건·엑셀 등록을 완료한다. 등록만으로 메일이 나가지 않는다.
2. 공용 발신 주소·이름을 조회·수정한다. 해당 SMTP 서비스에서 인증·허용된 발신 주소여야 한다.
3. 학생을 선택해 로그인 안내 발송을 요청한다. 최대 500명. 이미 발송·처리 중인 학생은 기본 요청에서 건너뛴다.
4. 처리 상태를 조회하여 성공·실패를 확인한다. SENT는 SMTP 접수 성공이며 받은편지함 도착이나 열람을 보장하지 않는다.
5. 실패 건은 다시 요청할 수 있다. 분실·명시적 재발급은 reissue=true로 요청한다. 재발급 전 확인창에서 기존 비밀번호가 바뀐다는 것을 알린다.

## API
담당자 전용 /v1/scholars/login-notices:
- GET /sender → {fromAddress,senderName}, 미설정이면 null
- PUT /sender, JSON {fromAddress,senderName} → 저장한 설정. 메일 접속 계정·비밀값은 이 API로 받지 않는다.
- POST, JSON {scholarIds:[...],reissue?:false} → 202 {queued,items:[{scholarId,status,errorCode}]}
- GET ?scholarIds=1,2 → [{scholarId,status,errorCode}]
공통 응답 래퍼 success.data를 사용한다. 주소 입력 오류 400, 인증 401, 권한 403, 메일 연결·발신 설정 미비 503 MAIL_UNAVAILABLE.
동일 대상 중복 ID·500명 초과 400. 개별 없는 학생·잠김·탈퇴 등은 SKIPPED로 반환한다.

## 상태와 재발급
- PENDING → SENDING → SENT / FAILED
- SKIPPED: SCHOLAR_NOT_FOUND / ACCOUNT_DISABLED / PASSWORD_ALREADY_CHANGED / ALREADY_QUEUED / ALREADY_SENT
- 첫 발송은 이미 본인 비밀번호로 바꾼 학생을 건너뛴다. reissue=true는 분실한 본인 비밀번호도 새 임시 비밀번호로 교체한다.
- 등록 결과 화면·미리 받은 안내 파일의 비밀번호는 발송 후 무효가 된다. 프론트는 발송 요청 후 이전 비밀번호 표시·파일을 최신 안내처럼 재사용하지 않도록 처리한다.
- 최초 발송과 재발급 모두 발송 처리 시 서로 다른 12자 안전한 난수를 사용한다. 혼동 문자 0/O/1/l/I 제외, DB에는 해시만 저장한다.
- 재발급 후 새 로그인에는 이전 비밀번호를 쓸 수 없고 다음 로그인에서 변경이 필요하다.
- 현재 인증 코드가 발급한 기존 JWT는 만료까지 유지된다. 강제 로그아웃·서버 측 비밀번호 변경 전 경로 제한·임시 비밀번호 만료 정책은 승완 인증 담당과 별도 연결 필요. 인증 코드를 수정하지 않았다.
- SMTP 실패가 등록 실패로 바뀌지는 않는다. 발송 전에 비밀번호 해시를 커밋하므로 메일 실패 시에도 이전 비밀번호는 무효가 될 수 있고, 담당자가 다시 발급해야 한다.

## 신뢰성과 개인정보
등록 요청 응답·화면 상태를 발송에 재사용하지 않는다. DB에는 대상·요청자·재발급 여부·상태만 저장한다. 비밀번호 원문과 메일 본문은 저장·로그하지 않는다.
서버 재시작 후 PENDING부터 처리한다. 10분 이상 SENDING은 DELIVERY_UNKNOWN으로 표시하며 자동 재발급하지 않는다. SMTP 접수와 DB 완료 저장 사이 장애는 메일 도착 여부를 단정할 수 없다.
현재 최종 상태만 대상별로 유지하며 재발급 행위는 기존 audit_logs에 남긴다. 500명은 대기열로 접수하고 한 주기당 최대 25명씩 처리한다.

## 배포 및 프론트 인계
- V8 기수 생성 제약 이후 V9 발송 테이블 적용. KT-61 머지 후 이 브랜치를 main 기준으로 정리·최종 확인한다.
- 발신 SMTP 서비스는 미정. 실제 발송 설정 없이 운영 완료로 표시하지 않는다.
- SMTP_HOST/PORT/USERNAME/PASSWORD, SMTP_AUTH/STARTTLS와 LOGIN_MAIL_ENABLED, LOGIN_MAIL_LOGIN_URL을 서버 환경변수로 설정한다.
- 로컬 SMTP 외에는 SMTP_AUTH/STARTTLS를 끄지 않는다. 키·비밀번호는 깃·채팅에 남기지 않는다.
- deploy/compose.yaml은 수정하지 않았다. 승완이 app.environment에 위 변수를 전달하도록 추가해야 한다. 기본 LOGIN_MAIL_ENABLED=false.
- 프론트는 기존 등록 결과 또는 명단에서 학생 선택→발송 확인→202 접수→상태 조회, 발신 주소·이름 편집, 재발급 확인창을 연결한다. 기존 로그인 안내 파일은 보조 수단으로 유지 가능하다.
- 프론트 소스는 수정하지 않았다.
