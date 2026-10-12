-- =============================================================
--  scb_mon_agent 변경 v9 - 기존 데이터 유지
--    데몬 중단 중 프로세스 현황 : 데몬(scb_client)이 죽어도 서버의 프로세스가 살아 있으면
--    하트비트 감시자(클라이언트 1.7)가 대신 수집해 별도 화면(/fallback)에 표시, 데몬이 다시 뜨면 삭제
--  실행 : db\migrate-v9.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

CREATE TABLE tb_proc_fallback (
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    daemon_state    VARCHAR(10)      NULL                COMMENT '수집 당시 데몬 상태 (DEAD / STOPPED)',
    report_text     MEDIUMTEXT   NOT NULL                COMMENT '감시자가 보낸 수집 결과 ([metric] [proc] [service])',
    proc_cnt        INT          NOT NULL DEFAULT 0      COMMENT '주요 프로세스 건수',
    svc_cnt         INT          NOT NULL DEFAULT 0      COMMENT '서비스 건수',
    recv_cnt        INT          NOT NULL DEFAULT 1      COMMENT '받은 횟수 (데몬 중단 후)',
    first_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '데몬 중단 후 처음 받은 일시',
    collect_dt      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막으로 받은 일시',
    PRIMARY KEY (server_id),
    CONSTRAINT fk_procfb_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
) ENGINE = InnoDB COMMENT = '데몬 중단 중 프로세스 현황 (하트비트 감시자 수집, 데몬이 다시 뜨면 삭제)';
