-- V1 is immutable. Existing duplicates must be resolved before applying these constraints.
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE(email);
ALTER TABLE request_templates ADD CONSTRAINT uq_template_code UNIQUE(code);
ALTER TABLE request_fields ADD CONSTRAINT uq_request_field UNIQUE(request_id, field_key);
ALTER TABLE request_targets ADD CONSTRAINT uq_target UNIQUE(request_id, scholar_id);
ALTER TABLE submissions ADD CONSTRAINT uq_submission UNIQUE(request_id, scholar_id);
ALTER TABLE document_analysis_results ADD CONSTRAINT uq_analysis_attempt UNIQUE(file_id, attempt_number);
ALTER TABLE revision_request_fields ADD CONSTRAINT uq_revision_field UNIQUE(revision_request_id, field_key);
ALTER TABLE refresh_tokens ADD CONSTRAINT uq_refresh_hash UNIQUE(token_hash);
CREATE TABLE manager_programs (
 id BIGINT PRIMARY KEY, manager_id BIGINT NOT NULL, program_id BIGINT NOT NULL, cohort_id BIGINT NULL,
 FOREIGN KEY(manager_id) REFERENCES users(id), FOREIGN KEY(program_id) REFERENCES programs(id),
 FOREIGN KEY(cohort_id) REFERENCES cohorts(id)
);
CREATE INDEX ix_manager_scope ON manager_programs(manager_id, program_id, cohort_id);
CREATE TABLE access_tokens (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
 expires_at DATETIME NOT NULL, revoked_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(user_id) REFERENCES users(id)
);
ALTER TABLE submission_files ADD COLUMN superseded_at DATETIME NULL;
CREATE INDEX ix_refresh_user ON refresh_tokens(user_id);
CREATE INDEX ix_access_user ON access_tokens(user_id);
CREATE INDEX ix_request_status ON requests(status, closes_at);
CREATE INDEX ix_submission_status ON submissions(request_id, status);
