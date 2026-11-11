-- V2: id 자동 증가, 업무 중복 방지, 비밀번호 변경 필요 여부
-- V1은 수정하지 않는다. 기존 데이터의 id는 유지되고 다음 번호부터 자동 생성된다.

-- 1. id 자동 증가 (15개 테이블)
-- 다른 테이블이 참조하는 id는 FK 검사 중에 변경할 수 없어(MySQL ERROR 1833) 잠시 검사를 끈다.
-- FK 정의 자체는 삭제하지 않으므로 그대로 유지된다.
SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE `programs` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `cohorts` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `users` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `request_templates` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `requests` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `request_fields` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `request_targets` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `submissions` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `submission_files` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `submission_histories` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `revision_requests` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `revision_request_fields` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `document_analysis_results` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `refresh_tokens` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';
ALTER TABLE `audit_logs` MODIFY `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '아이디';

SET FOREIGN_KEY_CHECKS = 1;

-- 2. 업무 중복 방지
-- 장학생 1명은 요청 1건에 제출 1개
ALTER TABLE `submissions` ADD CONSTRAINT `UK_submissions_request_scholar` UNIQUE (`request_id`, `scholar_id`);
-- 같은 장학생을 같은 요청 대상에 두 번 넣지 않음
ALTER TABLE `request_targets` ADD CONSTRAINT `UK_request_targets_request_scholar` UNIQUE (`request_id`, `scholar_id`);
-- 한 요청 안의 항목 키는 하나
ALTER TABLE `request_fields` ADD CONSTRAINT `UK_request_fields_request_field_key` UNIQUE (`request_id`, `field_key`);
-- 한 보완 요청 안의 보완 항목 키는 하나
ALTER TABLE `revision_request_fields` ADD CONSTRAINT `UK_revision_request_fields_revision_field_key` UNIQUE (`revision_request_id`, `field_key`);
-- 템플릿 코드는 하나
ALTER TABLE `request_templates` ADD CONSTRAINT `UK_request_templates_code` UNIQUE (`code`);
-- 같은 파일의 같은 분석 회차는 하나
ALTER TABLE `document_analysis_results` ADD CONSTRAINT `UK_document_analysis_results_file_attempt` UNIQUE (`file_id`, `attempt_number`);
-- 같은 리프레시 토큰 해시는 하나
ALTER TABLE `refresh_tokens` ADD CONSTRAINT `UK_refresh_tokens_token_hash` UNIQUE (`token_hash`);

-- 삭제되지 않은 계정끼리만 이메일 중복을 막는다. 삭제된 계정의 이메일은 다시 사용할 수 있다.
-- active_email은 DB가 계산하는 칸이라 직접 값을 넣지 않는다. 삭제된 계정은 NULL이 되어 UNIQUE 검사에서 빠진다.
ALTER TABLE `users` ADD COLUMN `active_email` VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN `deleted_at` IS NULL THEN `email` END) COMMENT '삭제되지 않은 계정의 이메일 (중복 검사용)';
ALTER TABLE `users` ADD CONSTRAINT `UK_users_active_email` UNIQUE (`active_email`);

-- 3. 첫 로그인 비밀번호 변경 필요 여부
-- 기존 계정은 변경 필요 없음(FALSE). 임시 비밀번호로 등록한 장학생만 TRUE로 저장한다.
ALTER TABLE `users` ADD COLUMN `password_change_required` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '비밀번호 변경 필요 여부';
