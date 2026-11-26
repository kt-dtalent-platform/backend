-- 기존 번호의 숫자는 보존하고 구분 문자만 제거한다. 없는 숫자를 추측하거나 보충하지 않는다.
UPDATE scholar_profiles
SET phone = REGEXP_REPLACE(phone, '[^0-9]', '')
WHERE phone IS NOT NULL;
