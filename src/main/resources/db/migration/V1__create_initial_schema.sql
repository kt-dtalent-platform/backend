-- 기존 ERD의 16개 테이블 및 기본키
CREATE TABLE `programs` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`name`	VARCHAR(100)	NOT NULL	COMMENT '프로그램명',
	`description`	TEXT	NULL	COMMENT '설명',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자'
);

CREATE TABLE `revision_requests` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`submission_id`	BIGINT	NOT NULL	COMMENT '제출 아이디',
	`reason`	TEXT	NOT NULL	COMMENT '보완 사유',
	`requested_by`	BIGINT	NOT NULL	COMMENT '요청 관리자 아이디',
	`requested_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '요청 일자',
	`resolved_at`	DATETIME	NULL	COMMENT '재제출 일자'
);

CREATE TABLE `requests` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`program_id`	BIGINT	NOT NULL	COMMENT '프로그램 아이디',
	`cohort_id`	BIGINT	NULL	COMMENT '대상 기수 아이디 (NULL이면 프로그램 전체)',
	`template_id`	BIGINT	NOT NULL	COMMENT '템플릿 아이디',
	`title`	VARCHAR(200)	NOT NULL	COMMENT '업무명',
	`description`	TEXT	NULL	COMMENT '제출 안내',
	`academic_year`	INT	NOT NULL	COMMENT '대상 연도',
	`semester`	ENUM('1', '2', 'SUMMER', 'WINTER')	NOT NULL	COMMENT '대상 학기 (1, 2, SUMMER, WINTER)',
	`closes_at`	DATETIME	NOT NULL	COMMENT '마감 일시',
	`status`	ENUM('DRAFT', 'IN_PROGRESS', 'CLOSED')	NOT NULL	DEFAULT 'DRAFT'	COMMENT '업무 상태 (DRAFT, IN_PROGRESS, CLOSED)',
	`completion_message`	TEXT	NULL	COMMENT '제출 완료 안내문',
	`created_by`	BIGINT	NOT NULL	COMMENT '생성 관리자 아이디',
	`published_at`	DATETIME	NULL	COMMENT '공개 일자',
	`version`	INT	NOT NULL	DEFAULT 0	COMMENT '낙관적 락 버전',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자'
);

CREATE TABLE `revision_request_fields` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`revision_request_id`	BIGINT	NOT NULL	COMMENT '보완요청 아이디',
	`field_key`	VARCHAR(64)	NOT NULL	COMMENT '보완 대상 항목 키'
);

CREATE TABLE `audit_logs` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`actor_id`	BIGINT	NULL	COMMENT '수행자 아이디',
	`action`	VARCHAR(60)	NOT NULL	COMMENT '작업 (EXPORT_SUBMISSIONS, DOWNLOAD_FILE 등)',
	`target_type`	VARCHAR(40)	NOT NULL	COMMENT '대상 유형',
	`target_id`	BIGINT	NULL	COMMENT '대상 아이디',
	`row_count`	INT	NULL	COMMENT '대상 건수',
	`ip_address`	VARCHAR(45)	NULL	COMMENT '접속 IP',
	`detail`	JSON	NULL	COMMENT '상세',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수행 일자'
);

CREATE TABLE `submission_histories` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`submission_id`	BIGINT	NOT NULL	COMMENT '제출 아이디',
	`from_status`	ENUM('DRAFT', 'SUBMITTED', 'REVISION_REQUIRED', 'RESUBMITTED', 'CONFIRMED')	NULL	COMMENT '이전 상태 (DRAFT, SUBMITTED, REVISION_REQUIRED, RESUBMITTED, CONFIRMED) - 행 없음은 미제출',
	`to_status`	ENUM('DRAFT', 'SUBMITTED', 'REVISION_REQUIRED', 'RESUBMITTED', 'CONFIRMED')	NOT NULL	COMMENT '변경 상태 (DRAFT, SUBMITTED, REVISION_REQUIRED, RESUBMITTED, CONFIRMED) - 행 없음은 미제출',
	`actor_type`	ENUM('SCHOLAR', 'MANAGER', 'SYSTEM')	NOT NULL	COMMENT '행위자 유형 (SCHOLAR, MANAGER, SYSTEM)',
	`actor_id`	BIGINT	NOT NULL	COMMENT '행위자 아이디',
	`snapshot`	JSON	NOT NULL	COMMENT '변경 전 입력값·파일 스냅샷',
	`changed_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '변경 일자'
);

CREATE TABLE `cohorts` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`program_id`	BIGINT	NOT NULL	COMMENT '프로그램 아이디',
	`name`	VARCHAR(50)	NOT NULL	COMMENT '기수명',
	`started_on`	DATE	NULL	COMMENT '시작일',
	`ended_on`	DATE	NULL	COMMENT '종료일',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자'
);

CREATE TABLE `submissions` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`request_id`	BIGINT	NOT NULL	COMMENT '제출업무 아이디',
	`scholar_id`	BIGINT	NOT NULL	COMMENT '장학생 아이디',
	`status`	ENUM('DRAFT', 'SUBMITTED', 'REVISION_REQUIRED', 'RESUBMITTED', 'CONFIRMED')	NOT NULL	DEFAULT 'DRAFT'	COMMENT '제출 상태 (DRAFT, SUBMITTED, REVISION_REQUIRED, RESUBMITTED, CONFIRMED) - 행 없음은 미제출',
	`answers`	JSON	NOT NULL	COMMENT '입력값 (평점·만점·학점 등)',
	`submit_count`	INT	NOT NULL	DEFAULT 0	COMMENT '제출 회차',
	`is_late`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '마감 후 제출 여부',
	`manual_review_requested`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '수동 확인 요청 여부',
	`manual_review_reason`	TEXT	NULL	COMMENT '수동 확인 요청 사유',
	`notice_acknowledged_at`	DATETIME	NULL	COMMENT '문서 처리 안내 확인 일자',
	`submitted_at`	DATETIME	NULL	COMMENT '최초 제출 일자',
	`confirmed_at`	DATETIME	NULL	COMMENT '승인 일자',
	`confirmed_by`	BIGINT	NULL	COMMENT '승인 관리자 아이디',
	`version`	INT	NOT NULL	DEFAULT 0	COMMENT '낙관적 락 버전',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자'
);

CREATE TABLE `request_templates` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`code`	VARCHAR(40)	NOT NULL	COMMENT '템플릿 코드 (GRADE_CERTIFICATE)',
	`name`	VARCHAR(100)	NOT NULL	COMMENT '템플릿명',
	`default_fields`	JSON	NOT NULL	COMMENT '기본 입력항목 정의',
	`is_active`	BOOLEAN	NOT NULL	DEFAULT TRUE	COMMENT '사용 여부',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자'
);

CREATE TABLE `scholar_profiles` (
	`user_id`	BIGINT	NOT NULL	COMMENT '사용자 아이디',
	`cohort_id`	BIGINT	NOT NULL	COMMENT '기수 아이디',
	`school_name`	VARCHAR(100)	NOT NULL	COMMENT '학교',
	`major`	VARCHAR(100)	NULL	COMMENT '전공',
	`student_no`	VARCHAR(30)	NULL	COMMENT '학번',
	`phone`	VARCHAR(20)	NULL	COMMENT '연락처',
	`status`	ENUM('ENROLLED', 'LEAVE', 'GRADUATED', 'WITHDRAWN')	NOT NULL	DEFAULT 'ENROLLED'	COMMENT '장학생 상태 (ENROLLED, LEAVE, GRADUATED, WITHDRAWN)',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자'
);

CREATE TABLE `request_targets` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`request_id`	BIGINT	NOT NULL	COMMENT '제출업무 아이디',
	`scholar_id`	BIGINT	NOT NULL	COMMENT '장학생 아이디',
	`assigned_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '배정 일자 (공개 시점 스냅샷)'
);

CREATE TABLE `document_analysis_results` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`submission_id`	BIGINT	NOT NULL	COMMENT '제출 아이디',
	`file_id`	BIGINT	NOT NULL	COMMENT '분석 파일 아이디',
	`attempt_number`	INT	NOT NULL	COMMENT '분석 시도 회차',
	`status`	ENUM('REQUESTED', 'COMPLETED', 'FAILED')	NOT NULL	DEFAULT 'REQUESTED'	COMMENT '분석 상태 (REQUESTED, COMPLETED, FAILED)',
	`entered_values`	JSON	NOT NULL	COMMENT '분석 당시 입력값',
	`extracted_values`	JSON	NULL	COMMENT 'OCR 추출값',
	`comparisons`	JSON	NULL	COMMENT '항목별 비교 결과',
	`overall_result`	ENUM('MATCH', 'MISMATCH', 'UNREADABLE')	NULL	COMMENT '종합 결과 (MATCH, MISMATCH, UNREADABLE)',
	`failure_reason`	VARCHAR(255)	NULL	COMMENT '실패 사유',
	`analyzer_version`	VARCHAR(50)	NULL	COMMENT '분석기 버전',
	`requested_by`	BIGINT	NOT NULL	COMMENT '요청자 아이디',
	`requested_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '요청 일자',
	`completed_at`	DATETIME	NULL	COMMENT '완료 일자',
	`invalidated_at`	DATETIME	NULL	COMMENT '무효화 일자 (파일·입력값 변경 시)'
);

CREATE TABLE `refresh_tokens` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`user_id`	BIGINT	NOT NULL	COMMENT '사용자 아이디',
	`token_hash`	VARCHAR(255)	NOT NULL	COMMENT '토큰 해시',
	`expires_at`	DATETIME	NOT NULL	COMMENT '만료 일자',
	`revoked_at`	DATETIME	NULL	COMMENT '폐기 일자 (로그아웃)',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자'
);

CREATE TABLE `users` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`email`	VARCHAR(255)	NOT NULL	COMMENT '이메일',
	`password_hash`	VARCHAR(255)	NOT NULL	COMMENT '비밀번호 해시',
	`name`	VARCHAR(50)	NOT NULL	COMMENT '이름',
	`role`	ENUM('MANAGER', 'SCHOLAR')	NOT NULL	COMMENT '역할 (MANAGER, SCHOLAR)',
	`status`	ENUM('ACTIVE', 'INACTIVE', 'LOCKED')	NOT NULL	DEFAULT 'ACTIVE'	COMMENT '계정 상태 (ACTIVE, INACTIVE, LOCKED)',
	`failed_login_count`	INT	NOT NULL	DEFAULT 0	COMMENT '로그인 실패 횟수',
	`locked_until`	DATETIME	NULL	COMMENT '잠금 해제 시각',
	`last_login_at`	DATETIME	NULL	COMMENT '최근 로그인 일자',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '수정 일자',
	`deleted_at`	DATETIME	NULL	COMMENT '삭제 일자'
);

CREATE TABLE `submission_files` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`submission_id`	BIGINT	NOT NULL	COMMENT '제출 아이디',
	`field_key`	VARCHAR(64)	NOT NULL	COMMENT '첨부 항목 키',
	`s3_key`	VARCHAR(500)	NOT NULL	COMMENT '저장 경로 (비공개)',
	`original_name`	VARCHAR(255)	NOT NULL	COMMENT '원본 파일명',
	`content_type`	VARCHAR(100)	NOT NULL	COMMENT 'MIME 타입',
	`size_bytes`	BIGINT	NOT NULL	COMMENT '파일 크기',
	`checksum`	VARCHAR(64)	NULL	COMMENT 'SHA-256',
	`upload_status`	ENUM('PENDING', 'UPLOADED', 'DELETED')	NOT NULL	DEFAULT 'PENDING'	COMMENT '업로드 상태 (PENDING, UPLOADED, DELETED)',
	`uploaded_at`	DATETIME	NULL	COMMENT '업로드 완료 일자',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '생성 일자'
);

CREATE TABLE `request_fields` (
	`id`	BIGINT	NOT NULL	COMMENT '아이디',
	`request_id`	BIGINT	NOT NULL	COMMENT '제출업무 아이디',
	`field_key`	VARCHAR(64)	NOT NULL	COMMENT '항목 키',
	`label`	VARCHAR(100)	NOT NULL	COMMENT '항목명',
	`field_type`	ENUM('TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'SELECT_ONE', 'SELECT_MULTI', 'CHECKBOX', 'FILE')	NOT NULL	COMMENT '입력 타입 (TEXT, NUMBER, SELECT_ONE, FILE 등)',
	`required`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '필수 여부',
	`options`	JSON	NULL	COMMENT '선택지',
	`validation`	JSON	NULL	COMMENT '검증 규칙 (확장자·용량·범위)',
	`help_text`	VARCHAR(255)	NULL	COMMENT '도움말',
	`sort_order`	INT	NOT NULL	DEFAULT 0	COMMENT '정렬 순서'
);

ALTER TABLE `programs` ADD CONSTRAINT `PK_PROGRAMS` PRIMARY KEY (
	`id`
);

ALTER TABLE `revision_requests` ADD CONSTRAINT `PK_REVISION_REQUESTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `requests` ADD CONSTRAINT `PK_REQUESTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `revision_request_fields` ADD CONSTRAINT `PK_REVISION_REQUEST_FIELDS` PRIMARY KEY (
	`id`
);

ALTER TABLE `audit_logs` ADD CONSTRAINT `PK_AUDIT_LOGS` PRIMARY KEY (
	`id`
);

ALTER TABLE `submission_histories` ADD CONSTRAINT `PK_SUBMISSION_HISTORIES` PRIMARY KEY (
	`id`
);

ALTER TABLE `cohorts` ADD CONSTRAINT `PK_COHORTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `submissions` ADD CONSTRAINT `PK_SUBMISSIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `request_templates` ADD CONSTRAINT `PK_REQUEST_TEMPLATES` PRIMARY KEY (
	`id`
);

ALTER TABLE `scholar_profiles` ADD CONSTRAINT `PK_SCHOLAR_PROFILES` PRIMARY KEY (
	`user_id`
);

ALTER TABLE `request_targets` ADD CONSTRAINT `PK_REQUEST_TARGETS` PRIMARY KEY (
	`id`
);

ALTER TABLE `document_analysis_results` ADD CONSTRAINT `PK_DOCUMENT_ANALYSIS_RESULTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `refresh_tokens` ADD CONSTRAINT `PK_REFRESH_TOKENS` PRIMARY KEY (
	`id`
);

ALTER TABLE `users` ADD CONSTRAINT `PK_USERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `submission_files` ADD CONSTRAINT `PK_SUBMISSION_FILES` PRIMARY KEY (
	`id`
);

ALTER TABLE `request_fields` ADD CONSTRAINT `PK_REQUEST_FIELDS` PRIMARY KEY (
	`id`
);

ALTER TABLE `scholar_profiles` ADD CONSTRAINT `FK_users_TO_scholar_profiles_1` FOREIGN KEY (
	`user_id`
)
REFERENCES `users` (
	`id`
);

-- ERD 관계선에 대응하는 외래키
ALTER TABLE `refresh_tokens`
    ADD CONSTRAINT `fk_refresh_tokens_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `audit_logs`
    ADD CONSTRAINT `fk_audit_logs_actor_id`
    FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`);

ALTER TABLE `cohorts`
    ADD CONSTRAINT `fk_cohorts_program_id`
    FOREIGN KEY (`program_id`) REFERENCES `programs` (`id`);

ALTER TABLE `scholar_profiles`
    ADD CONSTRAINT `fk_scholar_profiles_cohort_id`
    FOREIGN KEY (`cohort_id`) REFERENCES `cohorts` (`id`);

ALTER TABLE `requests`
    ADD CONSTRAINT `fk_requests_program_id`
    FOREIGN KEY (`program_id`) REFERENCES `programs` (`id`);

ALTER TABLE `requests`
    ADD CONSTRAINT `fk_requests_cohort_id`
    FOREIGN KEY (`cohort_id`) REFERENCES `cohorts` (`id`);

ALTER TABLE `requests`
    ADD CONSTRAINT `fk_requests_template_id`
    FOREIGN KEY (`template_id`) REFERENCES `request_templates` (`id`);

ALTER TABLE `requests`
    ADD CONSTRAINT `fk_requests_created_by`
    FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `request_fields`
    ADD CONSTRAINT `fk_request_fields_request_id`
    FOREIGN KEY (`request_id`) REFERENCES `requests` (`id`);

ALTER TABLE `request_targets`
    ADD CONSTRAINT `fk_request_targets_request_id`
    FOREIGN KEY (`request_id`) REFERENCES `requests` (`id`);

ALTER TABLE `request_targets`
    ADD CONSTRAINT `fk_request_targets_scholar_id`
    FOREIGN KEY (`scholar_id`) REFERENCES `scholar_profiles` (`user_id`);

ALTER TABLE `submissions`
    ADD CONSTRAINT `fk_submissions_request_id`
    FOREIGN KEY (`request_id`) REFERENCES `requests` (`id`);

ALTER TABLE `submissions`
    ADD CONSTRAINT `fk_submissions_scholar_id`
    FOREIGN KEY (`scholar_id`) REFERENCES `scholar_profiles` (`user_id`);

ALTER TABLE `submissions`
    ADD CONSTRAINT `fk_submissions_confirmed_by`
    FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`);

ALTER TABLE `submission_files`
    ADD CONSTRAINT `fk_submission_files_submission_id`
    FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`id`);

ALTER TABLE `document_analysis_results`
    ADD CONSTRAINT `fk_document_analysis_results_submission_id`
    FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`id`);

ALTER TABLE `document_analysis_results`
    ADD CONSTRAINT `fk_document_analysis_results_file_id`
    FOREIGN KEY (`file_id`) REFERENCES `submission_files` (`id`);

ALTER TABLE `document_analysis_results`
    ADD CONSTRAINT `fk_document_analysis_results_requested_by`
    FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`);

ALTER TABLE `submission_histories`
    ADD CONSTRAINT `fk_submission_histories_submission_id`
    FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`id`);

ALTER TABLE `revision_requests`
    ADD CONSTRAINT `fk_revision_requests_submission_id`
    FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`id`);

ALTER TABLE `revision_requests`
    ADD CONSTRAINT `fk_revision_requests_requested_by`
    FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`);

ALTER TABLE `revision_request_fields`
    ADD CONSTRAINT `fk_revision_request_fields_revision_request_id`
    FOREIGN KEY (`revision_request_id`) REFERENCES `revision_requests` (`id`);

