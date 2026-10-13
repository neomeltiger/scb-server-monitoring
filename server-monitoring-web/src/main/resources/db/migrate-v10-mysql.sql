-- =============================================================
--  scb_mon_agent v10 : 좌측 메뉴 · 메뉴 권한 (권한관리)
--  웹이 시작할 때 tb_menu 가 없으면 스스로 만들므로 이 파일은 미리 만들어 둘 때만 실행
--  기본 메뉴 행은 웹이 넣음 (menu.service.MenuService.DEFAULTS)
-- =============================================================
USE scb_mon_agent;

-- -------------------------------------------------------------
-- 좌측 메뉴 · 메뉴 권한 (v10) - 기본 메뉴는 웹이 시작할 때 넣음 (menu.service.MenuService)
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tb_menu (
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
