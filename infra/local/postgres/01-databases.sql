-- 로컬에서 서비스의 데이터 소유 경계를 구분합니다.
-- 최초 volume 생성 시 실행됩니다. Data와 Ingestion은 weavo_data를 사용합니다.
CREATE DATABASE weavo_auth;
CREATE DATABASE weavo_control;
CREATE DATABASE weavo_data;
