-- 기존 휴학·졸업 기록은 보존한다. 신규 수료는 COMPLETED로 저장한다.
ALTER TABLE scholar_profiles
    MODIFY COLUMN status ENUM('ENROLLED','LEAVE','GRADUATED','WITHDRAWN','COMPLETED') NOT NULL DEFAULT 'ENROLLED';
ALTER TABLE scholar_profiles ADD COLUMN completed_year SMALLINT NULL;
ALTER TABLE scholar_profiles ADD COLUMN withdrawal_reason VARCHAR(32) NULL;
ALTER TABLE scholar_profiles ADD COLUMN withdrawal_reason_detail VARCHAR(500) NULL;
