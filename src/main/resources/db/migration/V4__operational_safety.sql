-- 삭제 작업
CREATE TABLE file_deletion_jobs (
 id BIGINT PRIMARY KEY, storage_key VARCHAR(64) NOT NULL UNIQUE, file_id BIGINT NULL,
 requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at DATETIME NULL,
 attempt_count INT NOT NULL DEFAULT 0, next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(64) NULL
);
CREATE INDEX ix_file_deletion_pending ON file_deletion_jobs(completed_at, next_attempt_at);

-- 파일 보호 상태
ALTER TABLE submission_files ADD COLUMN storage_format VARCHAR(20) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE submission_files ADD COLUMN scan_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE requests ADD COLUMN purged_at DATETIME NULL;
ALTER TABLE audit_logs ADD COLUMN request_id VARCHAR(36) NULL;

-- 세션 수명
CREATE TABLE auth_sessions (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, absolute_expires_at DATETIME NOT NULL,
 revoked_at DATETIME NULL, mfa_verified BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE INDEX ix_auth_session_user ON auth_sessions(user_id, revoked_at);
ALTER TABLE access_tokens ADD COLUMN session_id BIGINT NULL;
ALTER TABLE refresh_tokens ADD COLUMN session_id BIGINT NULL;
ALTER TABLE access_tokens ADD CONSTRAINT fk_access_session FOREIGN KEY(session_id) REFERENCES auth_sessions(id);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_session FOREIGN KEY(session_id) REFERENCES auth_sessions(id);
UPDATE access_tokens SET revoked_at=CURRENT_TIMESTAMP WHERE revoked_at IS NULL;
UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP WHERE revoked_at IS NULL;

-- 초기 비밀번호 및 다중 인증
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN temporary_password_expires_at DATETIME NULL;
ALTER TABLE users ADD COLUMN mfa_secret_ciphertext TEXT NULL;
ALTER TABLE users ADD COLUMN mfa_last_step BIGINT NOT NULL DEFAULT -1;
UPDATE users SET must_change_password=TRUE;
CREATE TABLE auth_challenges (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
 kind VARCHAR(16) NOT NULL, secret_ciphertext TEXT NULL, expires_at DATETIME NOT NULL,
 used_at DATETIME NULL, attempts INT NOT NULL DEFAULT 0, FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE mfa_recovery_codes (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, code_hash VARCHAR(64) NOT NULL UNIQUE,
 used_at DATETIME NULL, FOREIGN KEY(user_id) REFERENCES users(id)
);

-- 공유 요청 제한
CREATE TABLE request_budgets (
 key_hash VARCHAR(64) PRIMARY KEY, window_start BIGINT NOT NULL, hit_count INT NOT NULL
);
