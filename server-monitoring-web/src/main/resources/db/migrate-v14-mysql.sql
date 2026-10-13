-- =============================================================
--  scb_mon_agent v14 : 서버 점검 리포트 자동 요청 주기 (서버 점검 리포트 화면)
--  웹이 시작할 때 없으면 스스로 추가하므로 (config.db.SchemaUpgrade) 미리 만들어 둘 때만 실행
-- =============================================================
USE scb_mon_agent;

CREATE TABLE IF NOT EXISTS tb_report_policy (
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    interval_hr     INT          NOT NULL DEFAULT 0      COMMENT '자동 요청 주기 (시간, 0 = 사용 안 함)',
    sections        VARCHAR(30)  NOT NULL                COMMENT '분야 (sec,res,svc,chg)',
    next_dt         TIMESTAMP        NULL                COMMENT '다음 자동 요청 일시',
    last_req_dt     TIMESTAMP        NULL                COMMENT '마지막 자동 요청 일시',
    upd_user        VARCHAR(30)      NULL                COMMENT '설정한 관리자',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경 일시',
    PRIMARY KEY (server_id),
    CONSTRAINT fk_rptpol_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
) ENGINE = InnoDB COMMENT = '서버 점검 리포트 자동 요청 주기 (관리자 설정)';
