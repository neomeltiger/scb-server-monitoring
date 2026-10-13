-- =============================================================
--  scb_mon_agent v11 : 관리자 계정 생성(임시 비밀번호) · 사용자별 메뉴 권한
--  웹이 시작할 때 없으면 스스로 추가하므로 (config.db.SchemaUpgrade) 미리 만들어 둘 때만 실행
-- =============================================================
USE scb_mon_agent;

ALTER TABLE tb_user ADD pw_change_yn   CHAR(1) NOT NULL DEFAULT 'N' COMMENT '임시 비밀번호 - 첫 로그인 때 변경 필요';
ALTER TABLE tb_user ADD menu_custom_yn CHAR(1) NOT NULL DEFAULT 'N' COMMENT '사용자별 메뉴 권한 사용 (tb_user_menu)';

CREATE TABLE IF NOT EXISTS tb_user_menu (
    user_id         VARCHAR(30)  NOT NULL COMMENT '사용자 ID',
    menu_id         VARCHAR(30)  NOT NULL COMMENT '허용 메뉴 ID (tb_menu)',
    reg_user        VARCHAR(30)           COMMENT '지정한 관리자',
    reg_dt          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '지정 일시',
    PRIMARY KEY (user_id, menu_id)
) ENGINE = InnoDB COMMENT = '사용자별 메뉴 권한';
