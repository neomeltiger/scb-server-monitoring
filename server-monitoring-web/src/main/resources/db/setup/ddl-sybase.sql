-- =============================================================
--  scb_mon_agent DDL for Sybase ASE (DB 구조 v13)
--  신규 DB 구성 화면(시스템관리 › DB 초기 구성)이 만든 스키마 안에서 실행. 테이블 22개
--  자동 생성 파일 - 고치지 말고 schema-ansi.sql / schema-mysql.sql 을 고친 뒤 DbSetupScriptsTest 로 다시 쓰기
-- =============================================================

CREATE TABLE tb_net_node (
    node_id         INT          NOT NULL,
    node_type       VARCHAR(30)  NOT NULL,
    node_nm         VARCHAR(150)  NOT NULL,
    node_desc       VARCHAR(300) NULL,
    site_cd         VARCHAR(30) NULL,
    location_nm     VARCHAR(150) NULL,
    parent_node_id  INT NULL,
    sort_ord        INT          DEFAULT 0 NOT NULL,
    PRIMARY KEY (node_id),
    CONSTRAINT fk_net_node_parent FOREIGN KEY (parent_node_id) REFERENCES tb_net_node (node_id),
    CONSTRAINT ck_net_node_type   CHECK (node_type IN ('HUB', 'SWITCH'))
);

CREATE TABLE tb_server (
    server_id       BIGINT IDENTITY,
    system_nm       VARCHAR(300) NOT NULL,
    fqdn            VARCHAR(765) NOT NULL,
    os_type         VARCHAR(90)  DEFAULT 'SOLARIS' NOT NULL,
    ip_addr         VARCHAR(135)  NOT NULL,
    switch_node_id  INT          NOT NULL,
    status_cd       VARCHAR(30)  NOT NULL,
    sync_status     VARCHAR(150) NULL,
    pair_server_id  BIGINT NULL,
    sort_ord        INT          DEFAULT 0 NOT NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (server_id),
    CONSTRAINT uk_server_ip      UNIQUE (ip_addr),
    CONSTRAINT fk_server_switch  FOREIGN KEY (switch_node_id) REFERENCES tb_net_node (node_id),
    CONSTRAINT fk_server_pair    FOREIGN KEY (pair_server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_server_status  CHECK (status_cd IN ('Active', 'Standby', 'Down'))
);

CREATE TABLE tb_server_metric (
    server_id       BIGINT       NOT NULL,
    cpu_pct         DECIMAL(5,1) NULL,
    mem_pct         DECIMAL(5,1) NULL,
    disk_pct        DECIMAL(5,1) NULL,
    session_cnt     INT NULL,
    collect_dt      DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (server_id),
    CONSTRAINT fk_metric_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
);

CREATE TABLE tb_server_process (
    proc_id         BIGINT IDENTITY,
    server_id       BIGINT       NOT NULL,
    proc_nm         VARCHAR(300) NOT NULL,
    exec_path       VARCHAR(765) NOT NULL,
    pid             INT NULL,
    cpu_pct         DECIMAL(5,1) DEFAULT 0 NOT NULL,
    mem_pct         DECIMAL(5,1) DEFAULT 0 NOT NULL,
    running_yn      CHAR(1)      DEFAULT 'N' NOT NULL,
    sort_ord        INT          DEFAULT 0 NOT NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (proc_id),
    CONSTRAINT fk_process_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_process_running CHECK (running_yn IN ('Y', 'N'))
);

CREATE INDEX ix_server_process_server ON tb_server_process (server_id, sort_ord);

CREATE TABLE tb_user (
    user_id         VARCHAR(90)  NOT NULL,
    user_nm         VARCHAR(150)  NOT NULL,
    password_hash   VARCHAR(300) NOT NULL,
    role_cd         VARCHAR(30)  DEFAULT 'USER' NOT NULL,
    use_yn          CHAR(1)      DEFAULT 'Y' NOT NULL,
    lock_yn         CHAR(1)      DEFAULT 'N' NOT NULL,
    appr_status     VARCHAR(30)  DEFAULT 'APPROVED' NOT NULL,
    email           VARCHAR(300) NULL,
    dept_nm         VARCHAR(150) NULL,
    req_reason      VARCHAR(600) NULL,
    appr_user       VARCHAR(90) NULL,
    appr_dt         DATETIME NULL,
    reject_reason   VARCHAR(600) NULL,
    fail_cnt        INT          DEFAULT 0 NOT NULL,
    last_login_dt   DATETIME NULL,
    last_login_ip   VARCHAR(135) NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    pw_change_yn    CHAR(1)      DEFAULT 'N' NOT NULL,
    menu_custom_yn  CHAR(1)      DEFAULT 'N' NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT ck_user_role CHECK (role_cd IN ('ADMIN', 'USER')),
    CONSTRAINT ck_user_use  CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_user_lock CHECK (lock_yn IN ('Y', 'N')),
    CONSTRAINT ck_user_appr CHECK (appr_status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE tb_login_hist (
    hist_id         BIGINT       IDENTITY,
    user_id         VARCHAR(90)  NOT NULL,
    login_dt        DATETIME    DEFAULT getdate() NOT NULL,
    client_ip       VARCHAR(135) NULL,
    result_cd       VARCHAR(30)  NOT NULL,
    message         VARCHAR(600) NULL,
    PRIMARY KEY (hist_id)
);

CREATE INDEX ix_login_hist_user ON tb_login_hist (user_id, login_dt);

CREATE TABLE tb_agent (
    server_id       BIGINT       NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    issue_dt        DATETIME    DEFAULT getdate() NOT NULL,
    issue_user      VARCHAR(90) NULL,
    last_report_dt  DATETIME NULL,
    last_report_ip  VARCHAR(135) NULL,
    agent_host      VARCHAR(300) NULL,
    agent_os        VARCHAR(300) NULL,
    agent_ver       VARCHAR(60) NULL,
    peer_status     VARCHAR(300) NULL,
    collect_yn           CHAR(1)      DEFAULT 'Y' NOT NULL,
    collect_interval_sec INT          DEFAULT 60 NOT NULL,
    shutdown_req_yn      CHAR(1)      DEFAULT 'N' NOT NULL,
    daemon_state         VARCHAR(30) NULL,
    daemon_pid           INT NULL,
    daemon_start_dt      DATETIME NULL,
    last_poll_dt         DATETIME NULL,
    ctl_user             VARCHAR(90) NULL,
    ctl_dt               DATETIME NULL,
    hb_interval_sec      INT          DEFAULT 10 NOT NULL,
    hb_state             VARCHAR(30) NULL,
    last_hb_dt           DATETIME NULL,
    hb_pid               INT NULL,
    hb_daemon_state      VARCHAR(30) NULL,
    restart_req_yn       CHAR(1)      DEFAULT 'N' NOT NULL,
    restart_req_user     VARCHAR(90) NULL,
    restart_req_dt       DATETIME NULL,
    boot_auto_yn         CHAR(1) NULL,
    boot_time            VARCHAR(90) NULL,
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
);

CREATE TABLE tb_server_metric_hist (
    hist_id         BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    collect_dt      DATETIME    NOT NULL,
    cpu_pct         DECIMAL(5,1) NULL,
    mem_pct         DECIMAL(5,1) NULL,
    disk_pct        DECIMAL(5,1) NULL,
    session_cnt     INT NULL,
    src_cd          VARCHAR(30)  DEFAULT 'AGENT' NOT NULL,
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_metric_hist_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
);

CREATE INDEX ix_metric_hist_server ON tb_server_metric_hist (server_id, collect_dt);

CREATE TABLE tb_process_metric_hist (
    hist_id         BIGINT       IDENTITY,
    proc_id         BIGINT       NOT NULL,
    server_id       BIGINT       NOT NULL,
    collect_dt      DATETIME    NOT NULL,
    cpu_pct         DECIMAL(5,1) DEFAULT 0 NOT NULL,
    mem_pct         DECIMAL(5,1) DEFAULT 0 NOT NULL,
    running_yn      CHAR(1)      DEFAULT 'N' NOT NULL,
    src_cd          VARCHAR(30)  DEFAULT 'AGENT' NOT NULL,
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_proc_hist_process FOREIGN KEY (proc_id) REFERENCES tb_server_process (proc_id)
);

CREATE INDEX ix_proc_hist_proc ON tb_process_metric_hist (proc_id, collect_dt);

CREATE INDEX ix_proc_hist_server ON tb_process_metric_hist (server_id, collect_dt);

CREATE TABLE tb_service (
    svc_id          BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    list_no         INT          NOT NULL,
    group_no        INT          NOT NULL,
    level_no        INT          NOT NULL,
    svc_nm          VARCHAR(150)  NOT NULL,
    account_nm      VARCHAR(90)  NOT NULL,
    script_path     VARCHAR(765) NOT NULL,
    script_arg      VARCHAR(300) NULL,
    cmds            VARCHAR(300) NOT NULL,
    state_cd        VARCHAR(30)  DEFAULT 'UNKNOWN' NOT NULL,
    pid             INT NULL,
    state_dt        DATETIME NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (svc_id),
    CONSTRAINT uk_service_name   UNIQUE (server_id, svc_nm),
    CONSTRAINT fk_service_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_service_state  CHECK (state_cd IN ('RUNNING', 'STOPPED', 'UNKNOWN'))
);

CREATE TABLE tb_service_cmd (
    cmd_id          BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    target          VARCHAR(180)  NOT NULL,
    cmd_cd          VARCHAR(30)  NOT NULL,
    state_cd        VARCHAR(30)  DEFAULT 'REQUESTED' NOT NULL,
    req_user        VARCHAR(90)  NOT NULL,
    req_dt          DATETIME    DEFAULT getdate() NOT NULL,
    sent_dt         DATETIME NULL,
    end_dt          DATETIME NULL,
    exit_cd         INT NULL,
    result_msg      VARCHAR(1900) NULL,
    PRIMARY KEY (cmd_id),
    CONSTRAINT fk_service_cmd_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_service_cmd_state  CHECK (state_cd IN ('REQUESTED', 'SENT', 'DONE', 'FAILED', 'EXPIRED'))
);

CREATE INDEX ix_service_cmd_server ON tb_service_cmd (server_id, state_cd, req_dt);

CREATE TABLE tb_alarm (
    alarm_id        BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    alarm_type      VARCHAR(60)  NOT NULL,
    alarm_level     VARCHAR(30)  NOT NULL,
    message         VARCHAR(600) NOT NULL,
    state_cd        VARCHAR(30)  DEFAULT 'OPEN' NOT NULL,
    occur_dt        DATETIME    DEFAULT getdate() NOT NULL,
    ack_user        VARCHAR(90) NULL,
    ack_dt          DATETIME NULL,
    action_memo     VARCHAR(1500) NULL,
    close_dt        DATETIME NULL,
    PRIMARY KEY (alarm_id),
    CONSTRAINT fk_alarm_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_alarm_type   CHECK (alarm_type IN ('SERVER_DOWN', 'AGENT_STALE', 'DAEMON_DOWN')),
    CONSTRAINT ck_alarm_level  CHECK (alarm_level IN ('CRITICAL', 'WARNING')),
    CONSTRAINT ck_alarm_state  CHECK (state_cd IN ('OPEN', 'ACK', 'CLOSED'))
);

CREATE INDEX ix_alarm_state ON tb_alarm (state_cd, server_id, alarm_type);

CREATE TABLE tb_log_policy (
    server_id       BIGINT       NOT NULL,
    interval_min    INT          DEFAULT 5 NOT NULL,
    req_now_yn      CHAR(1)      DEFAULT 'N' NOT NULL,
    last_req_dt     DATETIME NULL,
    next_req_dt     DATETIME NULL,
    last_recv_dt    DATETIME NULL,
    upd_user        VARCHAR(90) NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (server_id),
    CONSTRAINT fk_logpol_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logpol_interval CHECK (interval_min IN (0, 1, 5, 10, 30, 60)),
    CONSTRAINT ck_logpol_now      CHECK (req_now_yn IN ('Y', 'N'))
);

CREATE TABLE tb_log_source (
    log_id          BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    log_nm          VARCHAR(150)  NOT NULL,
    log_path        VARCHAR(765) NOT NULL,
    tail_lines      INT          DEFAULT 200 NOT NULL,
    use_yn          CHAR(1)      DEFAULT 'Y' NOT NULL,
    sort_ord        INT          DEFAULT 0 NOT NULL,
    last_status     VARCHAR(30) NULL,
    last_dt         DATETIME NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (log_id),
    CONSTRAINT uk_logsrc_path   UNIQUE (server_id, log_path),
    CONSTRAINT fk_logsrc_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logsrc_lines  CHECK (tail_lines BETWEEN 10 AND 1000),
    CONSTRAINT ck_logsrc_use    CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE tb_log_snapshot (
    snap_id         BIGINT       IDENTITY,
    log_id          BIGINT       NOT NULL,
    server_id       BIGINT       NOT NULL,
    collect_dt      DATETIME    DEFAULT getdate() NOT NULL,
    status_cd       VARCHAR(30)  NOT NULL,
    line_cnt        INT          DEFAULT 0 NOT NULL,
    byte_cnt        INT          DEFAULT 0 NOT NULL,
    content         TEXT NULL,
    PRIMARY KEY (snap_id),
    CONSTRAINT fk_logsnap_src    FOREIGN KEY (log_id) REFERENCES tb_log_source (log_id),
    CONSTRAINT fk_logsnap_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_logsnap_status CHECK (status_cd IN ('OK', 'NOT_FOUND', 'DENIED', 'ERROR'))
);

CREATE INDEX ix_logsnap_log ON tb_log_snapshot (log_id, collect_dt);

CREATE TABLE tb_server_report (
    report_id       BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    sections        VARCHAR(90)  NOT NULL,
    status_cd       VARCHAR(30)  NOT NULL,
    req_user        VARCHAR(90) NULL,
    req_dt          DATETIME    DEFAULT getdate() NOT NULL,
    sent_dt         DATETIME NULL,
    done_dt         DATETIME NULL,
    warn_cnt        INT          DEFAULT 0 NOT NULL,
    byte_cnt        INT          DEFAULT 0 NOT NULL,
    content         TEXT NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT fk_srvrpt_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_srvrpt_status CHECK (status_cd IN ('REQUESTED', 'SENT', 'DONE', 'ERROR'))
);

CREATE INDEX ix_srvrpt_server ON tb_server_report (server_id, req_dt);

CREATE TABLE tb_service_metric_hist (
    hist_id         BIGINT       IDENTITY,
    svc_id          BIGINT       NOT NULL,
    server_id       BIGINT       NOT NULL,
    collect_dt      DATETIME    DEFAULT getdate() NOT NULL,
    cpu_pct         DECIMAL(5,1) NULL,
    mem_pct         DECIMAL(5,1) NULL,
    running_yn      CHAR(1)      DEFAULT 'Y' NOT NULL,
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_svchist_svc     FOREIGN KEY (svc_id) REFERENCES tb_service (svc_id),
    CONSTRAINT fk_svchist_server  FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_svchist_running CHECK (running_yn IN ('Y', 'N'))
);

CREATE INDEX ix_svchist_svc ON tb_service_metric_hist (svc_id, collect_dt);

CREATE INDEX ix_svchist_server ON tb_service_metric_hist (server_id, collect_dt);

CREATE TABLE tb_heartbeat_hist (
    hist_id         BIGINT       IDENTITY,
    server_id       BIGINT       NOT NULL,
    event_cd        VARCHAR(30)  NOT NULL,
    event_dt        DATETIME    DEFAULT getdate() NOT NULL,
    daemon_pid      INT NULL,
    gap_sec         INT NULL,
    detail          VARCHAR(600) NULL,
    PRIMARY KEY (hist_id),
    CONSTRAINT fk_hbhist_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_hbhist_event  CHECK (event_cd IN ('START', 'LOST', 'RECOVER', 'STOP', 'INTERVAL', 'D_DOWN', 'D_UP', 'RESTART'))
);

CREATE INDEX ix_hbhist_server ON tb_heartbeat_hist (server_id, event_dt);

CREATE INDEX ix_hbhist_dt ON tb_heartbeat_hist (event_dt);

CREATE TABLE tb_proc_fallback (
    server_id       BIGINT       NOT NULL,
    daemon_state    VARCHAR(30) NULL,
    report_text     TEXT         NOT NULL,
    proc_cnt        INT          DEFAULT 0 NOT NULL,
    svc_cnt         INT          DEFAULT 0 NOT NULL,
    recv_cnt        INT          DEFAULT 1 NOT NULL,
    first_dt        DATETIME    DEFAULT getdate() NOT NULL,
    collect_dt      DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (server_id),
    CONSTRAINT fk_procfb_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id)
);

CREATE TABLE tb_menu (
    menu_id         VARCHAR(90)  NOT NULL,
    parent_id       VARCHAR(90) NULL,
    menu_type       VARCHAR(30)  NOT NULL,
    menu_nm         VARCHAR(150)  NOT NULL,
    default_nm      VARCHAR(150)  NOT NULL,
    url             VARCHAR(600) NULL,
    icon            VARCHAR(30) NULL,
    sort_ord        INT          DEFAULT 0 NOT NULL,
    user_yn         CHAR(1)      DEFAULT 'Y' NOT NULL,
    admin_only      CHAR(1)      DEFAULT 'N' NOT NULL,
    new_window      CHAR(1)      DEFAULT 'N' NOT NULL,
    upd_user        VARCHAR(90) NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (menu_id)
);

CREATE TABLE tb_user_menu (
    user_id         VARCHAR(90)  NOT NULL,
    menu_id         VARCHAR(90)  NOT NULL,
    reg_user        VARCHAR(90) NULL,
    reg_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (user_id, menu_id)
);

CREATE TABLE tb_retention (
    category_cd     VARCHAR(60)  NOT NULL,
    keep_days       INT          NOT NULL,
    upd_user        VARCHAR(90) NULL,
    upd_dt          DATETIME    DEFAULT getdate() NOT NULL,
    PRIMARY KEY (category_cd),
    CONSTRAINT ck_retention_days CHECK (keep_days BETWEEN 1 AND 3650)
);

