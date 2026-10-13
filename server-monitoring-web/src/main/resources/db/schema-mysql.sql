-- =============================================================
--  scb_mon_agent : 네트워크 구성도(network_topology) 화면용 테이블 (MySQL 8.x)
--
--  화면 구성 → 테이블
--    Central Hub / ITC Switch / DRC Switch 노드    → tb_net_node
--    서버 노드 (시스템명, FQDN, OS, IP, 설치장소,  → tb_server
--              상태, 동기화 상태, 동기화 대상)
--    상태 팝업 / 상세 패널의 CPU·MEM·DISK·세션수   → tb_server_metric
--    상세 패널의 주요 프로세스                       → tb_server_process
--  추가 (페이지 개발)
--    로그인 / 로그인 이력                            → tb_user, tb_login_hist
--    서버 에이전트(svr_collect_client) 토큰·상태      → tb_agent
--    메인 홈 서버별 그래프 (수집 이력)               → tb_server_metric_hist, tb_process_metric_hist
--    프로세스 관리 화면 (service.list, 제어 명령)    → tb_service, tb_service_cmd
--    메인 홈 서버 장애 알람 / 조치 기록              → tb_alarm
--    사용자 가입 신청 / 관리자 승인                   → tb_user (appr_status 등)
--    서버 로그 (주기 요청 / 수집 대상 / 수집 결과)    → tb_log_policy, tb_log_source, tb_log_snapshot
--
--  실행: db\init-mysql.bat  (또는 mysql -u root -p < schema-mysql.sql)
-- =============================================================
CREATE DATABASE IF NOT EXISTS scb_mon_agent
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE scb_mon_agent;

DROP TABLE IF EXISTS tb_menu;
DROP TABLE IF EXISTS tb_proc_fallback;
DROP TABLE IF EXISTS tb_heartbeat_hist;
DROP TABLE IF EXISTS tb_log_snapshot;
DROP TABLE IF EXISTS tb_log_source;
DROP TABLE IF EXISTS tb_log_policy;
DROP TABLE IF EXISTS tb_alarm;
DROP TABLE IF EXISTS tb_service_metric_hist;
DROP TABLE IF EXISTS tb_service_cmd;
DROP TABLE IF EXISTS tb_service;
DROP TABLE IF EXISTS tb_process_metric_hist;
DROP TABLE IF EXISTS tb_server_metric_hist;
DROP TABLE IF EXISTS tb_agent;
DROP TABLE IF EXISTS tb_login_hist;
DROP TABLE IF EXISTS tb_user;
DROP TABLE IF EXISTS tb_server_process;
DROP TABLE IF EXISTS tb_server_metric;
DROP TABLE IF EXISTS tb_server;
DROP TABLE IF EXISTS tb_net_node;

-- -------------------------------------------------------------
-- 네트워크 노드 : 라우터(Hub), 사이트 스위치(ITC / DRC)
-- -------------------------------------------------------------
CREATE TABLE tb_net_node (
    node_id         INT          NOT NULL                COMMENT '노드 ID (1=Central Hub, 10=ITC Switch, 20=DRC Switch)',
    node_type       VARCHAR(10)  NOT NULL                COMMENT '노드 종류 (HUB / SWITCH)',
    node_nm         VARCHAR(50)  NOT NULL                COMMENT '노드명 (화면 라벨 1행)',
    node_desc       VARCHAR(100)     NULL                COMMENT '노드 설명 (화면 라벨 2행)',
    site_cd         VARCHAR(10)      NULL                COMMENT '사이트 코드 (ITC / DRC, 스위치만)',
    location_nm     VARCHAR(50)      NULL                COMMENT '설치장소 (GASAN MTR-A / DRC, 스위치만)',
    parent_node_id  INT              NULL                COMMENT '상위 노드 ID (스위치 → Hub)',
    sort_ord        INT          NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    PRIMARY KEY (node_id),
    CONSTRAINT fk_net_node_parent FOREIGN KEY (parent_node_id) REFERENCES tb_net_node (node_id),
    CONSTRAINT ck_net_node_type   CHECK (node_type IN ('HUB', 'SWITCH'))
) ENGINE = InnoDB COMMENT = '네트워크 구성도 노드 (Hub / Switch)';

-- -------------------------------------------------------------
-- 서버
-- -------------------------------------------------------------
CREATE TABLE tb_server (
    server_id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '서버 ID (기본 서버 11~25, 신규 100 부터)',
    system_nm       VARCHAR(100) NOT NULL                COMMENT '시스템명',
    fqdn            VARCHAR(255) NOT NULL                COMMENT 'FQDN',
    os_type         VARCHAR(30)  NOT NULL DEFAULT 'SOLARIS' COMMENT 'OS 종류',
    ip_addr         VARCHAR(45)  NOT NULL                COMMENT 'IP 주소',
    switch_node_id  INT          NOT NULL                COMMENT '연결 스위치 = 설치장소 (tb_net_node)',
    status_cd       VARCHAR(10)  NOT NULL                COMMENT '시스템 구분 (Active / Standby / Down)',
    sync_status     VARCHAR(50)      NULL                COMMENT '동기화 상태',
    pair_server_id  BIGINT           NULL                COMMENT '동기화 대상 서버 ID',
    sort_ord        INT          NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (server_id),
    CONSTRAINT uk_server_ip      UNIQUE (ip_addr),
    CONSTRAINT fk_server_switch  FOREIGN KEY (switch_node_id) REFERENCES tb_net_node (node_id),
    CONSTRAINT fk_server_pair    FOREIGN KEY (pair_server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_server_status  CHECK (status_cd IN ('Active', 'Standby', 'Down'))
) ENGINE = InnoDB AUTO_INCREMENT = 100 COMMENT = '서버';

-- -------------------------------------------------------------
-- 서버 자원 현황 (서버당 1건, 최신 값)
-- -------------------------------------------------------------
CREATE TABLE tb_server_metric (
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    cpu_pct         DECIMAL(5,1)     NULL                COMMENT 'CPU 사용률 (%)',
    mem_pct         DECIMAL(5,1)     NULL                COMMENT '메모리 사용률 (%)',
    disk_pct        DECIMAL(5,1)     NULL                COMMENT '디스크 사용률 (%), NULL = N/A',
    session_cnt     INT              NULL                COMMENT '활성 세션수',
    collect_dt      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수집 일시',
    PRIMARY KEY (server_id),
    CONSTRAINT fk_metric_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
) ENGINE = InnoDB COMMENT = '서버 자원 현황';

-- -------------------------------------------------------------
-- 서버별 프로세스
-- -------------------------------------------------------------
CREATE TABLE tb_server_process (
    proc_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '프로세스 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    proc_nm         VARCHAR(100) NOT NULL                COMMENT '프로세스명',
    exec_path       VARCHAR(255) NOT NULL                COMMENT '실행 파일 경로',
    pid             INT              NULL                COMMENT 'PID (중지 시 NULL)',
    cpu_pct         DECIMAL(5,1) NOT NULL DEFAULT 0      COMMENT 'CPU 사용률 (%)',
    mem_pct         DECIMAL(5,1) NOT NULL DEFAULT 0      COMMENT '메모리 사용률 (%)',
    running_yn      CHAR(1)      NOT NULL DEFAULT 'N'    COMMENT '실행 여부 (Y / N)',
    sort_ord        INT          NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (proc_id),
    CONSTRAINT fk_process_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_process_running CHECK (running_yn IN ('Y', 'N'))
) ENGINE = InnoDB AUTO_INCREMENT = 1000 COMMENT = '서버별 프로세스';

CREATE INDEX ix_server_process_server ON tb_server_process (server_id, sort_ord);

-- =============================================================
--  추가 테이블 (페이지 개발)
-- =============================================================

-- -------------------------------------------------------------
-- 사용자 (로그인 화면, 가입 신청 / 관리자 승인)
-- -------------------------------------------------------------
CREATE TABLE tb_user (
    user_id         VARCHAR(30)  NOT NULL                COMMENT '로그인 ID',
    user_nm         VARCHAR(50)  NOT NULL                COMMENT '사용자명 (상단 표시)',
    password_hash   VARCHAR(100) NOT NULL                COMMENT '비밀번호 해시 ({bcrypt}...)',
    role_cd         VARCHAR(10)  NOT NULL DEFAULT 'USER' COMMENT '권한 (ADMIN: 변경/제어 가능, USER: 조회)',
    use_yn          CHAR(1)      NOT NULL DEFAULT 'Y'    COMMENT '사용 여부',
    lock_yn         CHAR(1)      NOT NULL DEFAULT 'N'    COMMENT '잠금 여부 (로그인 5회 실패 시 Y)',
    appr_status     VARCHAR(10)  NOT NULL DEFAULT 'APPROVED' COMMENT '가입 상태 (PENDING: 승인 대기 / APPROVED: 승인 / REJECTED: 반려)',
    email           VARCHAR(100)     NULL                COMMENT '이메일',
    dept_nm         VARCHAR(50)      NULL                COMMENT '부서',
    req_reason      VARCHAR(200)     NULL                COMMENT '가입 신청 사유',
    appr_user       VARCHAR(30)      NULL                COMMENT '승인/반려 처리자',
    appr_dt         TIMESTAMP        NULL                COMMENT '승인/반려 일시',
    reject_reason   VARCHAR(200)     NULL                COMMENT '반려 사유',
    fail_cnt        INT          NOT NULL DEFAULT 0      COMMENT '연속 로그인 실패 횟수',
    last_login_dt   TIMESTAMP        NULL                COMMENT '마지막 로그인 일시',
    last_login_ip   VARCHAR(45)      NULL                COMMENT '마지막 로그인 IP',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
    pw_change_yn    CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '임시 비밀번호 - 첫 로그인 때 변경 필요',
    menu_custom_yn  CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '사용자별 메뉴 권한 사용 (tb_user_menu)',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_user_role CHECK (role_cd IN ('ADMIN', 'USER')),
    CONSTRAINT ck_user_use  CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_user_lock CHECK (lock_yn IN ('Y', 'N')),
    CONSTRAINT ck_user_appr CHECK (appr_status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE = InnoDB COMMENT = '사용자';

-- -------------------------------------------------------------
-- 로그인 이력
-- -------------------------------------------------------------
CREATE TABLE tb_login_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    user_id         VARCHAR(30)  NOT NULL                COMMENT '입력한 로그인 ID',
    login_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '시도 일시',
    client_ip       VARCHAR(45)      NULL                COMMENT '접속 IP',
    result_cd       VARCHAR(10)  NOT NULL                COMMENT '결과 (SUCCESS / FAIL / LOCKED / DISABLED / PENDING / REJECTED)',
    message         VARCHAR(200)     NULL                COMMENT '비고',
    PRIMARY KEY (hist_id)
) ENGINE = InnoDB COMMENT = '로그인 이력';

CREATE INDEX ix_login_hist_user ON tb_login_hist (user_id, login_dt);

-- -------------------------------------------------------------
-- 서버 에이전트 (svr_collect_client) : 토큰, 마지막 보고 상태
-- -------------------------------------------------------------
CREATE TABLE tb_agent (
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    token_hash      CHAR(64)     NOT NULL                COMMENT '에이전트 토큰 SHA-256 (토큰 원문은 저장하지 않음)',
    issue_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '토큰 발급 일시',
    issue_user      VARCHAR(30)      NULL                COMMENT '발급자',
    last_report_dt  TIMESTAMP        NULL                COMMENT '마지막 보고 일시',
    last_report_ip  VARCHAR(45)      NULL                COMMENT '마지막 보고 IP',
    agent_host      VARCHAR(100)     NULL                COMMENT '보고된 호스트명',
    agent_os        VARCHAR(100)     NULL                COMMENT '보고된 OS',
    agent_ver       VARCHAR(20)      NULL                COMMENT '에이전트 버전',
    peer_status     VARCHAR(100)     NULL                COMMENT '동기화 대상 서버 통신 상태',
    collect_yn           CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '자료 수집 (Y: 시작 / N: 중지) - 웹에서 설정',
    collect_interval_sec INT          NOT NULL DEFAULT 60  COMMENT '수집 주기 (초) - 웹에서 설정',
    shutdown_req_yn      CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '데몬 종료 요청 (전달되면 N)',
    daemon_state         VARCHAR(10)      NULL COMMENT '데몬 상태 (RUNNING / PAUSED / STOPPING / STOPPED)',
    daemon_pid           INT              NULL COMMENT '데몬 PID',
    daemon_start_dt      TIMESTAMP        NULL COMMENT '데몬 시작 일시 (웹이 새 PID 를 처음 본 시각)',
    last_poll_dt         TIMESTAMP        NULL COMMENT '마지막 확인(heartbeat) 일시',
    ctl_user             VARCHAR(30)      NULL COMMENT '마지막 제어자',
    ctl_dt               TIMESTAMP        NULL COMMENT '마지막 제어 일시',
    hb_interval_sec      INT          NOT NULL DEFAULT 10 COMMENT '하트비트 주기 (초) - 네트워크 구성도에서 설정',
    hb_state             VARCHAR(10)      NULL COMMENT '하트비트 상태 (ALIVE: 수신 중 / LOST: 끊김 / STOPPED: 데몬 종료)',
    last_hb_dt           TIMESTAMP        NULL COMMENT '마지막 하트비트 수신 일시',
    hb_pid               INT              NULL COMMENT '하트비트를 보낸 데몬 PID',
    hb_daemon_state      VARCHAR(10)      NULL COMMENT '하트비트가 알린 데몬 상태 (RUNNING / PAUSED / STOPPED / DEAD) - 클라이언트 1.5~',
    restart_req_yn       CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '데몬 재시작 요청 (Y: 요청 / S: 서버에 전달 / N: 없음)',
    restart_req_user     VARCHAR(30)      NULL COMMENT '재시작 요청자',
    restart_req_dt       TIMESTAMP        NULL COMMENT '재시작 요청 일시',
    boot_auto_yn         CHAR(1)          NULL COMMENT '서버 부팅 시 자동 시작 설치 여부 (rc 스크립트, 하트비트 감시자가 알림)',
    boot_time            VARCHAR(30)      NULL COMMENT '서버 부팅 시각 (who -b, 바뀌면 재부팅 감지)',
    PRIMARY KEY (server_id),
    CONSTRAINT uk_agent_token  UNIQUE (token_hash),
    CONSTRAINT fk_agent_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_agent_collect  CHECK (collect_yn IN ('Y', 'N')),
    CONSTRAINT ck_agent_interval CHECK (collect_interval_sec IN (10, 30, 60, 120, 300, 600)),
    CONSTRAINT ck_agent_shutdown CHECK (shutdown_req_yn IN ('Y', 'N')),
    CONSTRAINT ck_agent_dstate   CHECK (daemon_state IN ('RUNNING', 'PAUSED', 'STOPPING', 'STOPPED')),
    CONSTRAINT ck_agent_hb_interval CHECK (hb_interval_sec IN (3, 5, 10, 15, 30, 60)),
    CONSTRAINT ck_agent_hb_state    CHECK (hb_state IN ('ALIVE', 'LOST', 'STOPPED')),
    CONSTRAINT ck_agent_hb_dstate   CHECK (hb_daemon_state IN ('RUNNING', 'PAUSED', 'STOPPED', 'DEAD')),
    CONSTRAINT ck_agent_restart     CHECK (restart_req_yn IN ('Y', 'S', 'N')),
    CONSTRAINT ck_agent_boot_auto   CHECK (boot_auto_yn IN ('Y', 'N'))
) ENGINE = InnoDB COMMENT = '서버 에이전트 (토큰, 마지막 보고, 데몬 제어/상태)';

-- -------------------------------------------------------------
-- 서버 자원 수집 이력 (메인 홈 서버별 그래프)
-- -------------------------------------------------------------
CREATE TABLE tb_server_metric_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    collect_dt      TIMESTAMP    NOT NULL                COMMENT '수집 일시',
    cpu_pct         DECIMAL(5,1)     NULL                COMMENT 'CPU 사용률 (%)',
    mem_pct         DECIMAL(5,1)     NULL                COMMENT '메모리 사용률 (%)',
    disk_pct        DECIMAL(5,1)     NULL                COMMENT '디스크 사용률 (%)',
    session_cnt     INT              NULL                COMMENT '세션수',
    src_cd          VARCHAR(10)  NOT NULL DEFAULT 'AGENT' COMMENT '출처 (AGENT: 에이전트 수집 / SAMPLE: 샘플)',
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_metric_hist_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
) ENGINE = InnoDB COMMENT = '서버 자원 수집 이력';

CREATE INDEX ix_metric_hist_server ON tb_server_metric_hist (server_id, collect_dt);

-- -------------------------------------------------------------
-- 프로세스 자원 수집 이력 (메인 홈 프로세스 그래프)
-- -------------------------------------------------------------
CREATE TABLE tb_process_metric_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    proc_id         BIGINT       NOT NULL                COMMENT '프로세스 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    collect_dt      TIMESTAMP    NOT NULL                COMMENT '수집 일시',
    cpu_pct         DECIMAL(5,1) NOT NULL DEFAULT 0      COMMENT 'CPU 사용률 (%)',
    mem_pct         DECIMAL(5,1) NOT NULL DEFAULT 0      COMMENT '메모리 사용률 (%)',
    running_yn      CHAR(1)      NOT NULL DEFAULT 'N'    COMMENT '실행 여부',
    src_cd          VARCHAR(10)  NOT NULL DEFAULT 'AGENT' COMMENT '출처 (AGENT / SAMPLE)',
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_proc_hist_process FOREIGN KEY (proc_id) REFERENCES tb_server_process (proc_id)
) ENGINE = InnoDB COMMENT = '프로세스 자원 수집 이력';

CREATE INDEX ix_proc_hist_proc ON tb_process_metric_hist (proc_id, collect_dt);
CREATE INDEX ix_proc_hist_server ON tb_process_metric_hist (server_id, collect_dt);

-- -------------------------------------------------------------
-- 서비스 목록 (서버의 svr_opt_agent/etc/service.list)
-- -------------------------------------------------------------
CREATE TABLE tb_service (
    svc_id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '서비스 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    list_no         INT          NOT NULL                COMMENT 'service.list 순번 (scb_main.sh idx 번호)',
    group_no        INT          NOT NULL                COMMENT 'GROUP (그룹제어순서)',
    level_no        INT          NOT NULL                COMMENT 'LEVEL (Control Level)',
    svc_nm          VARCHAR(50)  NOT NULL                COMMENT 'SERVICE_NAME',
    account_nm      VARCHAR(30)  NOT NULL                COMMENT 'ACCOUNT',
    script_path     VARCHAR(255) NOT NULL                COMMENT '제어 스크립트 전체 경로',
    script_arg      VARCHAR(100)     NULL                COMMENT 'ARG',
    cmds            VARCHAR(100) NOT NULL                COMMENT '지원 명령 (start|stop|test|kill|clean)',
    state_cd        VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '상태 (RUNNING / STOPPED / UNKNOWN)',
    pid             INT              NULL                COMMENT 'PID',
    state_dt        TIMESTAMP        NULL                COMMENT '상태 수집 일시',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    upd_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (svc_id),
    CONSTRAINT uk_service_name   UNIQUE (server_id, svc_nm),
    CONSTRAINT fk_service_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_service_state  CHECK (state_cd IN ('RUNNING', 'STOPPED', 'UNKNOWN'))
) ENGINE = InnoDB COMMENT = '서비스 목록 (service.list)';

-- -------------------------------------------------------------
-- 서비스 제어 명령 (화면 요청 → 에이전트가 가져가 scb_main.sh 실행 → 결과 보고)
-- -------------------------------------------------------------
CREATE TABLE tb_service_cmd (
    cmd_id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '명령 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    target          VARCHAR(60)  NOT NULL                COMMENT '대상 (all / group:N / level:N / 서비스명)',
    cmd_cd          VARCHAR(10)  NOT NULL                COMMENT '명령 (start / stop / restart / status / kill / clean)',
    state_cd        VARCHAR(10)  NOT NULL DEFAULT 'REQUESTED' COMMENT '상태 (REQUESTED / SENT / DONE / FAILED / EXPIRED)',
    req_user        VARCHAR(30)  NOT NULL                COMMENT '요청자',
    req_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '요청 일시',
    sent_dt         TIMESTAMP        NULL                COMMENT '에이전트 전달 일시',
    end_dt          TIMESTAMP        NULL                COMMENT '완료 일시',
    exit_cd         INT              NULL                COMMENT '종료 코드',
    result_msg      VARCHAR(4000)    NULL                COMMENT '실행 결과 (출력 마지막 4000자)',
    PRIMARY KEY (cmd_id),
    CONSTRAINT fk_service_cmd_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_service_cmd_state  CHECK (state_cd IN ('REQUESTED', 'SENT', 'DONE', 'FAILED', 'EXPIRED'))
) ENGINE = InnoDB COMMENT = '서비스 제어 명령';

CREATE INDEX ix_service_cmd_server ON tb_service_cmd (server_id, state_cd, req_dt);

-- -------------------------------------------------------------
-- 서버 장애 알람 (메인 홈 경고 / 조치 기록)
--   SERVER_DOWN : 서버 상태 Down (CRITICAL)
--   AGENT_STALE : 에이전트 보고 끊김 (WARNING)
--   OPEN(발생) → ACK(확인·조치 중) → CLOSED(조건 해소 시 자동)
-- -------------------------------------------------------------
CREATE TABLE tb_alarm (
    alarm_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '알람 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    alarm_type      VARCHAR(20)  NOT NULL                COMMENT '종류 (SERVER_DOWN / AGENT_STALE / DAEMON_DOWN)',
    alarm_level     VARCHAR(10)  NOT NULL                COMMENT '등급 (CRITICAL / WARNING)',
    message         VARCHAR(200) NOT NULL                COMMENT '알람 내용',
    state_cd        VARCHAR(10)  NOT NULL DEFAULT 'OPEN' COMMENT '상태 (OPEN / ACK / CLOSED)',
    occur_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발생 일시',
    ack_user        VARCHAR(30)      NULL                COMMENT '확인자',
    ack_dt          TIMESTAMP        NULL                COMMENT '확인 일시',
    action_memo     VARCHAR(500)     NULL                COMMENT '조치 내용',
    close_dt        TIMESTAMP        NULL                COMMENT '해소 일시',
    PRIMARY KEY (alarm_id),
    CONSTRAINT fk_alarm_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_alarm_type   CHECK (alarm_type IN ('SERVER_DOWN', 'AGENT_STALE', 'DAEMON_DOWN')),
    CONSTRAINT ck_alarm_level  CHECK (alarm_level IN ('CRITICAL', 'WARNING')),
    CONSTRAINT ck_alarm_state  CHECK (state_cd IN ('OPEN', 'ACK', 'CLOSED'))
) ENGINE = InnoDB COMMENT = '서버 장애 알람';

CREATE INDEX ix_alarm_state ON tb_alarm (state_cd, server_id, alarm_type);

-- -------------------------------------------------------------
-- 서버 로그 : 웹이 주기적으로 요청 → 에이전트가 로그 마지막 N줄 전송
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
-- 서비스(service.list) CPU / MEM 이력 : 메인 홈에서 골라서 그래프로 보기
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
-- 하트비트 이벤트 이력 (v6) : 수신 시작 / 끊김 / 복구 / 데몬 종료 / 주기 변경
-- -------------------------------------------------------------
CREATE TABLE tb_heartbeat_hist (
    hist_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    event_cd        VARCHAR(10)  NOT NULL                COMMENT '이벤트 (START 수신 시작 / LOST 끊김 / RECOVER 복구 / STOP 데몬 종료 / INTERVAL 주기 변경 / D_DOWN 데몬 중단 / D_UP 데몬 다시 실행 / RESTART 재시작 요청)',
    event_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발생 일시',
    daemon_pid      INT              NULL                COMMENT '데몬 PID',
    gap_sec         INT              NULL                COMMENT '마지막 수신 후 경과 (초)',
    detail          VARCHAR(200)     NULL                COMMENT '내용',
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_hbhist_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_hbhist_event  CHECK (event_cd IN ('START', 'LOST', 'RECOVER', 'STOP', 'INTERVAL', 'D_DOWN', 'D_UP', 'RESTART'))
) ENGINE = InnoDB COMMENT = '하트비트 이벤트 이력';

CREATE INDEX ix_hbhist_server ON tb_heartbeat_hist (server_id, event_dt);
CREATE INDEX ix_hbhist_dt ON tb_heartbeat_hist (event_dt);

-- -------------------------------------------------------------
-- 데몬 중단 중 프로세스 현황 (v9) : 데몬(scb_client)이 죽어도 하트비트 감시자가 대신 수집, 데몬이 다시 뜨면 삭제
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
-- 좌측 메뉴 · 메뉴 권한 (v10) - 기본 메뉴는 웹이 시작할 때 넣음 (menu.service.MenuService)
-- -------------------------------------------------------------
CREATE TABLE tb_menu (
    menu_id         VARCHAR(30)  NOT NULL COMMENT '메뉴 ID (main, network ...)',
    parent_id       VARCHAR(30)           COMMENT '그룹 메뉴 ID (NULL = 맨 위)',
    menu_type       VARCHAR(10)  NOT NULL COMMENT 'GROUP / PAGE / LINK',
    menu_nm         VARCHAR(50)  NOT NULL COMMENT '화면 표시 이름 (변경 가능)',
    default_nm      VARCHAR(50)  NOT NULL COMMENT '처음 이름',
    url             VARCHAR(200)          COMMENT '화면 주소',
    icon            VARCHAR(10)           COMMENT '아이콘',
    sort_ord        INT          NOT NULL DEFAULT 0 COMMENT '순서',
    user_yn         CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '일반 사용자 접근 허용',
    admin_only      CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '관리자 전용 고정',
    new_window      CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '새 창',
    upd_user        VARCHAR(30)           COMMENT '변경자',
    upd_dt          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경 일시',
    PRIMARY KEY (menu_id)
) ENGINE = InnoDB COMMENT = '좌측 메뉴 · 메뉴 권한';
