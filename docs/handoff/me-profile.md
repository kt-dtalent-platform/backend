# 승완 연결 작업

사용자 요청에 따라 AuthService·AuthController는 수정하지 않았다.
MeProfileRepository와 MeResponse의 기수·학교 응답 준비만 구현했다.

## 연결

1. AuthService에 MeProfileRepository를 주입한다.
2. getMe에서 로그인 사용자 역할이 SCHOLAR일 때만 자신의 프로필을 조회해 MeResponse.from의 세 번째 인수로 전달한다.
3. 함께 제공한 me-profile-auth-service.patch는 이 연결만 담은 검토용 패치이며 자동 적용하지 않았다.
4. AuthController의 'cohort는 현재 null' 설명도 실제 연결 후 수정한다.

## 응답

- 장학생: cohort={id,name}, university=저장된 school_name. 프로필이 없으면 둘 다 null.
- 담당자: accessiblePrograms 기존 계약 유지. cohort·university는 null.
- 계좌정보는 이 응답에 넣지 않는다.

## 완료 확인

연결 후 기존 로그인·담당자 /me 계약과 장학생 /me의 기수·학교, 없는 프로필, 탈퇴 계정 401을 통합 테스트하고 실제 프론트 /me 표시를 확인한다.
현재 브랜치만 적용하면 /v1/me의 기수·학교가 자동으로 채워지는 상태는 아니다.
