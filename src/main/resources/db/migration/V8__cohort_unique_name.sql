-- 같은 프로그램 안의 기수명 중복을 동시 요청에서도 방지한다. 기존 중복 데이터가 있다면 배포 전 확인한다.
ALTER TABLE cohorts ADD CONSTRAINT UK_cohorts_program_name UNIQUE (program_id, name);
