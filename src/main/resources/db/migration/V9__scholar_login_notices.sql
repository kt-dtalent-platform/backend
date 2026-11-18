-- 비밀번호 원문·메일 본문은 저장하지 않는다. 대기열에는 대상과 처리 상태만 남긴다.
CREATE TABLE login_notice_settings (
    id INT NOT NULL PRIMARY KEY,
    from_address VARCHAR(255) NOT NULL,
    sender_name VARCHAR(100) NOT NULL
);
CREATE TABLE scholar_login_notices (
    scholar_id BIGINT NOT NULL PRIMARY KEY,
    requested_by BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reissue BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(40) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_login_notices_scholar FOREIGN KEY (scholar_id) REFERENCES users(id),
    CONSTRAINT fk_login_notices_actor FOREIGN KEY (requested_by) REFERENCES users(id)
);
CREATE INDEX IX_login_notices_status_updated ON scholar_login_notices(status, updated_at);
