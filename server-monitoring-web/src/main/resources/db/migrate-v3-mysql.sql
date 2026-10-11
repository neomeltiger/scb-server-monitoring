-- =============================================================
--  scb_mon_agent 변경 v3 (서버웹수행요청서) - 기존 데이터 유지
--    서버 로그 주기 요청 : tb_log_policy, tb_log_source, tb_log_snapshot
--  실행 : db\migrate-v3.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

-- 서버별 로그 요청 주기 (행이 없으면 기본 5분)
CREATE TABLE tb_log_policy (
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    interval_min    INT          NOT NULL DEFAULT 5      COMMENT '요청 주기 (분, 0 = 사용 안 함)',
    req_now_yn      CHAR(1)      NOT NULL DEFAULT 'N'    COMMENT '즉시 요청 대기 (화면 [지금 요청])',
    last_req_dt     TIMESTAMP        NULL                COMMENT '마지막 요청(에이전트 전달) 일시',
    next_req_dt     TIMESTAMP        NULL                COMMENT '다음 요청 예정 일시',
    last_recv_dt    TIMESTAMP        NULL                COMMENT '마지막 로그 수신 일시',
    upd_user        VARCHAR(30)      NULL                COMMENT '변경자',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경 일시',
    PRIMARY KEY (server_id),
    CONSTRAINT fk_logpol_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logpol_interval CHECK (interval_min IN (0, 1, 5, 10, 30, 60)),
    CONSTRAINT ck_logpol_now      CHECK (req_now_yn IN ('Y', 'N'))
) ENGINE = InnoDB COMMENT = '서버 로그 요청 주기';

-- 서버별 수집 대상 로그 파일
CREATE TABLE tb_log_source (
    log_id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '로그 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    log_nm          VARCHAR(50)  NOT NULL                COMMENT '로그 이름 (화면 표시)',
    log_path        VARCHAR(255) NOT NULL                COMMENT '로그 파일 경로 (절대 경로)',
    tail_lines      INT          NOT NULL DEFAULT 200    COMMENT '가져올 마지막 줄 수',
    use_yn          CHAR(1)      NOT NULL DEFAULT 'Y'    COMMENT '수집 여부',
    sort_ord        INT          NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    last_status     VARCHAR(10)      NULL                COMMENT '마지막 수집 결과 (OK / NOT_FOUND / DENIED / ERROR)',
    last_dt         TIMESTAMP        NULL                COMMENT '마지막 수집 일시',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (log_id),
    CONSTRAINT uk_logsrc_path   UNIQUE (server_id, log_path),
    CONSTRAINT fk_logsrc_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logsrc_lines  CHECK (tail_lines BETWEEN 10 AND 1000),
    CONSTRAINT ck_logsrc_use    CHECK (use_yn IN ('Y', 'N'))
) ENGINE = InnoDB COMMENT = '서버 로그 수집 대상';

-- 수집된 로그 (로그별 최근 20회 보관)
CREATE TABLE tb_log_snapshot (
    snap_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '수집 ID',
    log_id          BIGINT       NOT NULL                COMMENT '로그 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    collect_dt      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수신 일시',
    status_cd       VARCHAR(10)  NOT NULL                COMMENT '결과 (OK / NOT_FOUND / DENIED / ERROR)',
    line_cnt        INT          NOT NULL DEFAULT 0      COMMENT '줄 수',
    byte_cnt        INT          NOT NULL DEFAULT 0      COMMENT '크기 (바이트)',
    content         MEDIUMTEXT       NULL                COMMENT '로그 내용 (마지막 N줄)',
    PRIMARY KEY (snap_id),
    CONSTRAINT fk_logsnap_src    FOREIGN KEY (log_id) REFERENCES tb_log_source (log_id),
    CONSTRAINT fk_logsnap_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logsnap_status CHECK (status_cd IN ('OK', 'NOT_FOUND', 'DENIED', 'ERROR'))
) ENGINE = InnoDB COMMENT = '서버 로그 수집 결과';

CREATE INDEX ix_logsnap_log ON tb_log_snapshot (log_id, collect_dt);
