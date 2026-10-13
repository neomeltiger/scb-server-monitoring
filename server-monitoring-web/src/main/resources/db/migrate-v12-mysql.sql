-- =============================================================
--  scb_mon_agent v12 : 서버 점검 리포트 (tb_server_report)
--  웹이 시작할 때 없으면 스스로 추가하므로 (config.db.SchemaUpgrade) 미리 만들어 둘 때만 실행
-- =============================================================
USE scb_mon_agent;

-- 서버 점검 리포트 (v12) : 웹 관리자 화면에서 요청할 때만 svr_collect_client 가 만들어 보냄 (서버별 최근 20건)
CREATE TABLE IF NOT EXISTS tb_server_report (
    report_id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '리포트 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    sections        VARCHAR(30)  NOT NULL                COMMENT '분야 (sec,res,svc,chg)',
    status_cd       VARCHAR(10)  NOT NULL                COMMENT '상태 (REQUESTED / SENT / DONE / ERROR)',
    req_user        VARCHAR(30)      NULL                COMMENT '요청 관리자',
    req_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '요청 일시',
    sent_dt         TIMESTAMP        NULL                COMMENT '에이전트 전달 일시',
    done_dt         TIMESTAMP        NULL                COMMENT '수신 일시',
    warn_cnt        INT          NOT NULL DEFAULT 0      COMMENT '경고 수',
    byte_cnt        INT          NOT NULL DEFAULT 0      COMMENT '크기 (바이트)',
    content         MEDIUMTEXT       NULL                COMMENT '리포트 내용',
    PRIMARY KEY (report_id),
    CONSTRAINT fk_srvrpt_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_srvrpt_status CHECK (status_cd IN ('REQUESTED', 'SENT', 'DONE', 'ERROR'))
) ENGINE = InnoDB COMMENT = '서버 점검 리포트';
CREATE INDEX ix_srvrpt_server ON tb_server_report (server_id, req_dt);
