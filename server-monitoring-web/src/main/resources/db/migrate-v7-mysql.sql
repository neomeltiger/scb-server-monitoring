-- =============================================================
--  scb_mon_agent 변경 v7 - 기존 데이터 유지
--    하트비트 감시자 (클라이언트 1.5) : 데몬이 죽어도 하트비트는 계속 보내며 데몬 상태를 함께 알림
--      → 하트비트 3회 연속 미수신 = 서버 다운, 데몬만 문제 = 서버는 정상 + 데몬 재시작 알림
--    tb_agent          + hb_daemon_state / restart_req_yn / restart_req_user / restart_req_dt
--    tb_heartbeat_hist   이벤트 + D_DOWN(데몬 중단) / D_UP(데몬 다시 실행) / RESTART(재시작 요청)
--    tb_alarm            종류 + DAEMON_DOWN (데몬 중단 - 서버 정상)
--  실행 : db\migrate-v7.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

ALTER TABLE tb_agent
    ADD COLUMN hb_daemon_state  VARCHAR(10)     NULL COMMENT '하트비트가 알린 데몬 상태 (RUNNING / PAUSED / STOPPED / DEAD) - 클라이언트 1.5~' AFTER hb_pid,
    ADD COLUMN restart_req_yn   CHAR(1)     NOT NULL DEFAULT 'N' COMMENT '데몬 재시작 요청 (Y: 요청 / S: 서버에 전달 / N: 없음)' AFTER hb_daemon_state,
    ADD COLUMN restart_req_user VARCHAR(30)     NULL COMMENT '재시작 요청자' AFTER restart_req_yn,
    ADD COLUMN restart_req_dt   TIMESTAMP       NULL COMMENT '재시작 요청 일시' AFTER restart_req_user,
    ADD CONSTRAINT ck_agent_hb_dstate CHECK (hb_daemon_state IN ('RUNNING', 'PAUSED', 'STOPPED', 'DEAD')),
    ADD CONSTRAINT ck_agent_restart   CHECK (restart_req_yn IN ('Y', 'S', 'N'));

ALTER TABLE tb_heartbeat_hist
    DROP CHECK ck_hbhist_event,
    ADD CONSTRAINT ck_hbhist_event CHECK (event_cd IN ('START', 'LOST', 'RECOVER', 'STOP', 'INTERVAL', 'D_DOWN', 'D_UP', 'RESTART')),
    MODIFY event_cd VARCHAR(10) NOT NULL COMMENT '이벤트 (START 수신 시작 / LOST 끊김 / RECOVER 복구 / STOP 데몬 종료 / INTERVAL 주기 변경 / D_DOWN 데몬 중단 / D_UP 데몬 다시 실행 / RESTART 재시작 요청)';

ALTER TABLE tb_alarm
    DROP CHECK ck_alarm_type,
    ADD CONSTRAINT ck_alarm_type CHECK (alarm_type IN ('SERVER_DOWN', 'AGENT_STALE', 'DAEMON_DOWN')),
    MODIFY alarm_type VARCHAR(20) NOT NULL COMMENT '종류 (SERVER_DOWN / AGENT_STALE / DAEMON_DOWN)';
