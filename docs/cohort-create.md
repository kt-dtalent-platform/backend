# 기수 생성

담당자 전용 POST /v1/programs/{programId}/cohorts.
요청: {name, startedOn?, endedOn?}. 이름은 앞뒤 공백 제거 후 최대 50자, 시작·종료일은 선택.
성공 201: {id,name,startedOn,endedOn,scholarCount:0}. 생성한 id를 즉시 단건·일괄 등록의 cohortId로 사용한다.
동일 프로그램에서 같은 이름은 409 DUPLICATE_COHORT. 다른 프로그램의 같은 이름은 허용한다.
날짜 순서·입력 오류 400, 미인증 401, 장학생 403, 프로그램 없음 404 PROGRAM_NOT_FOUND.

V8 migration 전 기존 중복을 확인한다:
SELECT program_id,name,COUNT(*) FROM cohorts GROUP BY program_id,name HAVING COUNT(*)>1;
중복이 있으면 기존 참조 데이터를 확인하여 정리한 뒤 배포한다. 임의로 기수를 삭제·합치지 않는다.
프론트 기존 새 기수 버튼 요청을 사용하며 프론트·인증 소스는 수정하지 않는다.
