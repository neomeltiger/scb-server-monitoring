-- =============================================================
--  scb_mon_agent 변경 v8 - 기존 데이터 유지
--    서버 복구 자동 감지 : 하트비트 감시자(클라이언트 1.6)가 부팅 자동 시작 설치 여부와 서버 부팅 시각을 알림
--      → 재부팅 감지, 부팅 자동 시작 미설치 경고 (서버가 되살아나도 데몬이 안 뜨는 경우)
--  실행 : db\migrate-v8.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

ALTER TABLE tb_agent
    ADD COLUMN boot_auto_yn CHAR(1)     NULL COMMENT '서버 부팅 시 자동 시작 설치 여부 (rc 스크립트, 하트비트 감시자가 알림)' AFTER restart_req_dt,
    ADD COLUMN boot_time    VARCHAR(30) NULL COMMENT '서버 부팅 시각 (who -b, 바뀌면 재부팅 감지)' AFTER boot_auto_yn,
    ADD CONSTRAINT ck_agent_boot_auto CHECK (boot_auto_yn IN ('Y', 'N'));
