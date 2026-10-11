-- =============================================================
--  scb_mon_agent 변경 v5 - 기존 데이터 유지
--    메인 홈 : 프로세스 관리(service.list) 서비스를 골라 그래프로 보기 → 서비스별 CPU / MEM 이력
--  실행 : db\migrate-v5.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

CREATE TABLE tb_service_metric_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    svc_id          BIGINT       NOT NULL                COMMENT '서비스 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    collect_dt      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수집 일시',
    cpu_pct         DECIMAL(5,1)     NULL                COMMENT 'CPU 사용률 (%) - 서비스 PID 의 ps pcpu',
    mem_pct         DECIMAL(5,1)     NULL                COMMENT '메모리 사용률 (%) - 서비스 PID 의 ps pmem',
    running_yn      CHAR(1)      NOT NULL DEFAULT 'Y'    COMMENT '실행 여부',
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_svchist_svc     FOREIGN KEY (svc_id) REFERENCES tb_service (svc_id),
    CONSTRAINT fk_svchist_server  FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_svchist_running CHECK (running_yn IN ('Y', 'N'))
) ENGINE = InnoDB COMMENT = '서비스(service.list) CPU / MEM 수집 이력';

CREATE INDEX ix_svchist_svc ON tb_service_metric_hist (svc_id, collect_dt);
CREATE INDEX ix_svchist_server ON tb_service_metric_hist (server_id, collect_dt);
