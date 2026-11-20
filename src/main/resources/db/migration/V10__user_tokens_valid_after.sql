-- 이 시각보다 먼저 발급된 access token은 거절한다 (비밀번호 변경·임시 비밀번호 재발급 뒤 기존 토큰 끊기)
ALTER TABLE users ADD COLUMN tokens_valid_after DATETIME NULL COMMENT '이 시각 전에 발급된 토큰은 무효';
