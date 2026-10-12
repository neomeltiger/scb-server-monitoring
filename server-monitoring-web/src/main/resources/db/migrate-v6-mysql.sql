-- =============================================================
--  scb_mon_agent 변경 v6 - 기존 데이터 유지
--    하트비트 : 클라이언트 1.4 의 bin/scb_heartbeat.sh 가 정한 주기마다 보내는 생존 신호
--      tb_agent          + hb_interval_sec / hb_state / last_hb_dt / hb_pid
--      tb_heartbeat_hist   하트비트 이벤트 이력 (수신 시작 / 끊김 / 복구 / 데몬 종료 / 주기 변경)
--  실행 : db\migrate-v6.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

ALTER TABLE tb_agent
    ADD COLUMN hb_interval_sec INT         NOT NULL DEFAULT 10 COMMENT '하트비트 주기 (초) - 네트워크 구성도에서 설정' AFTER ctl_dt,
    ADD COLUMN hb_state        VARCHAR(10)     NULL COMMENT '하트비트 상태 (ALIVE: 수신 중 / LOST: 끊김 / STOPPED: 데몬 종료)' AFTER hb_interval_sec,
    ADD COLUMN last_hb_dt      TIMESTAMP       NULL COMMENT '마지막 하트비트 수신 일시' AFTER hb_state,
    ADD COLUMN hb_pid          INT             NULL COMMENT '하트비트를 보낸 데몬 PID' AFTER last_hb_dt,
    ADD CONSTRAINT ck_agent_hb_interval CHECK (hb_interval_sec IN (3, 5, 10, 15, 30, 60)),
    ADD CONSTRAINT ck_agent_hb_state    CHECK (hb_state IN ('ALIVE', 'LOST', 'STOPPED'));

CREATE TABLE tb_heartbeat_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    event_cd        VARCHAR(10)  NOT NULL                COMMENT '이벤트 (START: 수신 시작 / LOST: 끊김 / RECOVER: 복구 / STOP: 데몬 종료 / INTERVAL: 주기 변경)',
    event_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발생 일시',
    daemon_pid      INT              NULL                COMMENT '데몬 PID',
    gap_sec         INT              NULL                COMMENT '마지막 수신 후 경과 (초)',
    detail          VARCHAR(200)     NULL                COMMENT '내용',
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_hbhist_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_hbhist_event  CHECK (event_cd IN ('START', 'LOST', 'RECOVER', 'STOP', 'INTERVAL'))
) ENGINE = InnoDB COMMENT = '하트비트 이벤트 이력';

CREATE INDEX ix_hbhist_server ON tb_heartbeat_hist (server_id, event_dt);
CREATE INDEX ix_hbhist_dt ON tb_heartbeat_hist (event_dt);
