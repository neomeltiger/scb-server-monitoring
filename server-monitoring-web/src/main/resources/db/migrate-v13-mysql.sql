-- =============================================================
--  scb_mon_agent v13 : 로그 · 이력 보관 기간 (환경 설정 화면)
--  웹이 시작할 때 없으면 스스로 추가하므로 (config.db.SchemaUpgrade) 미리 만들어 둘 때만 실행
-- =============================================================
USE scb_mon_agent;

CREATE TABLE IF NOT EXISTS tb_retention (
    category_cd     VARCHAR(20)  NOT NULL COMMENT '분류 (METRIC / LOG / HEARTBEAT / ALARM / COMMAND / LOGIN / REPORT)',
    keep_days       INT          NOT NULL COMMENT '최장 보관 일수 (1 ~ 3650)',
    upd_user        VARCHAR(30)           COMMENT '변경자',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경 일시',
    PRIMARY KEY (category_cd),
    CONSTRAINT ck_retention_days CHECK (keep_days BETWEEN 1 AND 3650)
) ENGINE = InnoDB COMMENT = '로그 · 이력 보관 기간';
