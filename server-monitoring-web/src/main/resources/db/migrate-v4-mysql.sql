-- =============================================================
--  scb_mon_agent 변경 v4 (개발요청서_추가변경) - 기존 데이터 유지
--    krcasmrx11_client 데몬 : 웹에서 시작 / 중지 / 종료, 수집 주기 설정 + 데몬 상태(heartbeat)
--  실행 : db\migrate-v4.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

ALTER TABLE tb_agent
    ADD COLUMN collect_yn           CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '자료 수집 (Y: 시작 / N: 중지) - 웹에서 설정' AFTER peer_status,
    ADD COLUMN collect_interval_sec INT          NOT NULL DEFAULT 60  COMMENT '수집 주기 (초) - 웹에서 설정' AFTER collect_yn,
    ADD COLUMN shutdown_req_yn      CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '데몬 종료 요청 (전달되면 N)' AFTER collect_interval_sec,
    ADD COLUMN daemon_state         VARCHAR(10)      NULL COMMENT '데몬 상태 (RUNNING / PAUSED / STOPPING / STOPPED)' AFTER shutdown_req_yn,
    ADD COLUMN daemon_pid           INT              NULL COMMENT '데몬 PID' AFTER daemon_state,
    ADD COLUMN daemon_start_dt      TIMESTAMP        NULL COMMENT '데몬 시작 일시 (웹이 새 PID 를 처음 본 시각)' AFTER daemon_pid,
    ADD COLUMN last_poll_dt         TIMESTAMP        NULL COMMENT '마지막 확인(heartbeat) 일시' AFTER daemon_start_dt,
    ADD COLUMN ctl_user             VARCHAR(30)      NULL COMMENT '마지막 제어자' AFTER last_poll_dt,
    ADD COLUMN ctl_dt               TIMESTAMP        NULL COMMENT '마지막 제어 일시' AFTER ctl_user,
    ADD CONSTRAINT ck_agent_collect  CHECK (collect_yn IN ('Y', 'N')),
    ADD CONSTRAINT ck_agent_interval CHECK (collect_interval_sec IN (10, 30, 60, 120, 300, 600)),
    ADD CONSTRAINT ck_agent_shutdown CHECK (shutdown_req_yn IN ('Y', 'N')),
    ADD CONSTRAINT ck_agent_dstate   CHECK (daemon_state IN ('RUNNING', 'PAUSED', 'STOPPING', 'STOPPED'));
