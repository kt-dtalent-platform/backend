-- 로컬 연동용 가상 데이터. 데이터가 없는 개발 DB에서 한 번만 실행합니다.
-- 운영/공용 DB 사용 금지. 기존 데이터 수정/삭제 없음.
START TRANSACTION;

INSERT INTO programs (id, name, description)
VALUES (900001, 'KT 디지털인재장학생 (로컬 테스트)', '프론트·백엔드 로컬 연동용 가상 데이터');

INSERT INTO cohorts (id, program_id, name)
VALUES (900001, 900001, '테스트 기수');

-- 로컬 전용 비밀번호: LocalDemo123! (공통 BCryptPasswordEncoder로 생성)
INSERT INTO users (id, email, password_hash, name, role, status) VALUES
(900001, 'admin@kt.com', '$2a$10$BGYrFL6kso4mBZ1EkbVB0e3Sqi2sksY8qY80aj68ixAinG8I4NFby', '테스트 담당자', 'MANAGER', 'ACTIVE'),
(900002, 'hong@kt.com', '$2a$10$BGYrFL6kso4mBZ1EkbVB0e3Sqi2sksY8qY80aj68ixAinG8I4NFby', '테스트 장학생', 'SCHOLAR', 'ACTIVE');

INSERT INTO scholar_profiles (user_id, cohort_id, school_name)
VALUES (900002, 900001, '테스트대학교');

-- 사용자 정의 템플릿: 미합의된 validation 키나 성적 항목을 가정하지 않습니다.
INSERT INTO request_templates (id, code, name, default_fields)
VALUES (900001, 'CUSTOM', '로컬 테스트용 사용자 정의', '[]');

COMMIT;
