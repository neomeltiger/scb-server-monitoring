-- =============================================================
--  scb_mon_agent 변경 v2 (개발요청서) - 기존 데이터 유지
--    1) 사용자 가입/승인 : tb_user 컬럼 추가
--    3) 서버 장애 알람   : tb_alarm 생성
--  실행 : db\migrate-v2.bat   (새로 설치할 때는 schema-mysql.sql 에 이미 포함되어 있어 필요 없음)
-- =============================================================
USE scb_mon_agent;

-- 1) 가입 신청 / 승인 정보
ALTER TABLE tb_user
    ADD COLUMN appr_status   VARCHAR(10)  NOT NULL DEFAULT 'APPROVED' COMMENT '가입 상태 (PENDING: 승인 대기 / APPROVED: 승인 / REJECTED: 반려)' AFTER lock_yn,
    ADD COLUMN email         VARCHAR(100)     NULL COMMENT '이메일' AFTER appr_status,
    ADD COLUMN dept_nm       VARCHAR(50)      NULL COMMENT '부서' AFTER email,
    ADD COLUMN req_reason    VARCHAR(200)     NULL COMMENT '가입 신청 사유' AFTER dept_nm,
    ADD COLUMN appr_user     VARCHAR(30)      NULL COMMENT '승인/반려 처리자' AFTER req_reason,
    ADD COLUMN appr_dt       TIMESTAMP        NULL COMMENT '승인/반려 일시' AFTER appr_user,
    ADD COLUMN reject_reason VARCHAR(200)     NULL COMMENT '반려 사유' AFTER appr_dt,
    ADD CONSTRAINT ck_user_appr CHECK (appr_status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- 3) 서버 장애 알람
CREATE TABLE tb_alarm (
    alarm_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '알람 ID',
    server_id       BIGINT       NOT NULL                COMMENT '서버 ID',
    alarm_type      VARCHAR(20)  NOT NULL                COMMENT '종류 (SERVER_DOWN: 서버 다운 / AGENT_STALE: 에이전트 보고 끊김)',
    alarm_level     VARCHAR(10)  NOT NULL                COMMENT '등급 (CRITICAL / WARNING)',
    message         VARCHAR(200) NOT NULL                COMMENT '알람 내용',
    state_cd        VARCHAR(10)  NOT NULL DEFAULT 'OPEN' COMMENT '상태 (OPEN: 발생 / ACK: 확인·조치 중 / CLOSED: 해소)',
    occur_dt        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발생 일시',
    ack_user        VARCHAR(30)      NULL                COMMENT '확인자',
    ack_dt          TIMESTAMP        NULL                COMMENT '확인 일시',
    action_memo     VARCHAR(500)     NULL                COMMENT '조치 내용',
    close_dt        TIMESTAMP        NULL                COMMENT '해소 일시',
    PRIMARY KEY (alarm_id),
    CONSTRAINT fk_alarm_server FOREIGN KEY (server_id) REFERENCES tb_server (server_id),
    CONSTRAINT ck_alarm_type   CHECK (alarm_type IN ('SERVER_DOWN', 'AGENT_STALE')),
    CONSTRAINT ck_alarm_level  CHECK (alarm_level IN ('CRITICAL', 'WARNING')),
    CONSTRAINT ck_alarm_state  CHECK (state_cd IN ('OPEN', 'ACK', 'CLOSED'))
) ENGINE = InnoDB COMMENT = '서버 장애 알람';

CREATE INDEX ix_alarm_state ON tb_alarm (state_cd, server_id, alarm_type);
