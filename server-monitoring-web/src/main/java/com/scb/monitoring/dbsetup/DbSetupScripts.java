package com.scb.monitoring.dbsetup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scb.monitoring.config.db.DbDialect;
import com.scb.monitoring.config.db.DbKind;
import com.scb.monitoring.config.db.DbSchema;
import com.scb.monitoring.menu.domain.Menu;

/**
 * 신규 DB 구성용 스크립트 (프로젝트 안 src/main/resources/db/setup/ 에 저장된 파일).
 * <pre>
 *  ddl-oracle.sql / ddl-sybase.sql / ddl-mysql.sql (MySQL · MariaDB) / ddl-h2.sql
 *      : schema-ansi.sql · schema-mysql.sql 의 CREATE TABLE · CREATE INDEX 만 (DROP · CREATE DATABASE · USE 없음)
 *  base-data.sql (ANSI, 실행할 때 DB 별로 바꿈 - DbDialect.data)
 *      : 웹 구동에 필요한 기초 데이터만 - 사용자(admin), 메뉴, 네트워크 노드, 서버, 주요 프로세스, 서비스(service.list),
 *        서버 로그 설정, 보관 기간. 수집 데이터 · 이력(자원 현황 · 각종 hist · 알람 · 명령 · 로그 수집본 · 리포트 등)은 넣지 않음
 * </pre>
 * 파일은 {@code DbSetupScriptsTest} 가 이 클래스로 만든 내용과 같은지 확인하고,
 * 스키마가 바뀌면 {@code mvn test -Dtest=DbSetupScriptsTest -Dscb.setup.write=true} 로 다시 씁니다.
 */
public final class DbSetupScripts {

    public static final String DIR = "db/setup/";
    /** 기초 데이터에 넣는 테이블 (data.sql 에서 고름) - 순서 = 입력 순서 */
    static final List<String> BASE_TABLES = Arrays.asList("tb_net_node", "tb_server", "tb_server_process", "tb_service",
            "tb_log_policy", "tb_log_source");
    /** 서버 정보 : 동기화 대상 서버 (UPDATE tb_server SET pair_server_id = 숫자) */
    private static final Pattern PAIR = Pattern.compile("(?is)^UPDATE\\s+tb_server\\s+SET\\s+pair_server_id\\s*=\\s*\\d+.*");
    private static final Pattern INSERT_TABLE = Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)\\b.*");
    /** 보관 기간 기본값 (retention.service.RetentionService 와 같음) */
    static final String[][] RETENTION = {{"ALARM", "365"}, {"COMMAND", "180"}, {"HEARTBEAT", "180"}, {"LOG", "30"},
            {"LOGIN", "365"}, {"METRIC", "7"}, {"REPORT", "180"}};

    private DbSetupScripts() {
    }

    /** 파일 이름 : DB 종류별 DDL */
    public static String ddlFile(DbKind kind) {
        return DIR + "ddl-" + (kind.mysqlFamily() ? "mysql" : kind.key()) + ".sql";
    }

    public static String baseDataFile() {
        return DIR + "base-data.sql";
    }

    // ===================== 만들기 (테스트 · 다시 쓰기용) =====================

    /** DB 종류별 DDL 파일 내용 */
    public static String generateDdl(DbKind kind) {
        List<String> stmts = new ArrayList<>();
        if (kind.mysqlFamily()) {
            for (String st : DbDialect.split(DbSchema.resource("db/schema-mysql.sql"))) {
                String u = st.toUpperCase(Locale.ROOT);
                if (u.startsWith("CREATE TABLE") || u.startsWith("CREATE INDEX") || u.startsWith("CREATE UNIQUE INDEX")) stmts.add(st);
            }
        } else {
            for (String st : DbDialect.ddl(DbSchema.resource(DbSchema.ANSI), kind)) {
                if (!st.toUpperCase(Locale.ROOT).startsWith("DROP ")) stmts.add(st);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-- =============================================================\n");
        sb.append("--  scb_mon_agent DDL for ").append(kind.mysqlFamily() ? "MySQL / MariaDB" : kind.label()).append(" (DB 구조 v14)\n");
        sb.append("--  신규 DB 구성 화면(시스템관리 › DB 초기 구성)이 만든 스키마 안에서 실행. 테이블 ").append(countTables(stmts)).append("개\n");
        sb.append("--  자동 생성 파일 - 고치지 말고 schema-ansi.sql / schema-mysql.sql 을 고친 뒤 DbSetupScriptsTest 로 다시 쓰기\n");
        sb.append("-- =============================================================\n\n");
        for (String st : stmts) sb.append(st.trim()).append(";\n\n");
        return sb.toString();
    }

    /** 기초 데이터 파일 내용 (ANSI) - menus = MenuService.DEFAULTS */
    public static String generateBaseData(List<Menu> menus) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- =============================================================\n");
        sb.append("--  scb_mon_agent 기초 데이터 (ANSI SQL) - 웹 구동에 필요한 것만\n");
        sb.append("--  사용자(admin) · 메뉴 · 네트워크 노드 · 서버 · 주요 프로세스 · 서비스 · 서버 로그 설정 · 보관 기간\n");
        sb.append("--  수집 데이터와 이력(자원 현황, 수집 이력, 하트비트, 알람, 제어 명령, 로그 수집본, 점검 리포트, 로그인 이력, 에이전트 토큰)은 넣지 않음\n");
        sb.append("--  admin / admin (데모 비밀번호, BCrypt) : 처음 로그인 후 반드시 바꾸세요.\n");
        sb.append("--  자동 생성 파일 - data.sql · users.sql · MenuService 기본 메뉴를 고친 뒤 DbSetupScriptsTest 로 다시 쓰기\n");
        sb.append("-- =============================================================\n\n");

        sb.append("-- 사용자\n");
        for (String st : DbDialect.split(DbSchema.resource("db/users.sql"))) {
            if (tableOf(st).equals("tb_user")) sb.append(st.trim()).append(";\n");
        }

        sb.append("\n-- 메뉴 (좌측 메뉴 · 메뉴 권한 기본값)\n");
        for (Menu m : menus) {
            sb.append("INSERT INTO tb_menu (menu_id, parent_id, menu_type, menu_nm, default_nm, url, icon, sort_ord, user_yn, admin_only, new_window, upd_user, upd_dt) VALUES (")
              .append(q(m.getMenuId())).append(", ").append(q(m.getParentId())).append(", ").append(q(m.getMenuType())).append(", ")
              .append(q(m.getDefaultNm())).append(", ").append(q(m.getDefaultNm())).append(", ").append(q(m.getUrl())).append(", ")
              .append(q(m.getIcon())).append(", ").append(m.getSortOrd()).append(", ").append(q(m.getUserYn())).append(", ")
              .append(q(m.getAdminOnly())).append(", ").append(q(m.getNewWindow())).append(", 'system', CURRENT_TIMESTAMP);\n");
        }

        List<String> data = DbDialect.split(DbSchema.resource("db/data.sql"));
        for (String table : BASE_TABLES) {
            List<String> rows = new ArrayList<>();
            for (String st : data) if (tableOf(st).equals(table)) rows.add(st.trim());
            if (rows.isEmpty()) continue;
            sb.append("\n-- ").append(table).append('\n');
            for (String r : rows) sb.append(r).append(";\n");
            if (table.equals("tb_server")) {
                sb.append("\n-- tb_server 동기화 대상 서버\n");
                for (String st : data) if (PAIR.matcher(st.trim()).matches()) sb.append(st.trim()).append(";\n");
            }
        }

        sb.append("\n-- 로그 · 이력 보관 기간 (기본값)\n");
        for (String[] r : RETENTION) {
            sb.append("INSERT INTO tb_retention (category_cd, keep_days, upd_user, upd_dt) VALUES ('").append(r[0]).append("', ").append(r[1])
              .append(", 'system', CURRENT_TIMESTAMP);\n");
        }
        return sb.toString();
    }

    /** 기초 데이터에 들어가는 테이블 (입력 순서) */
    public static Set<String> baseTables() {
        Set<String> s = new LinkedHashSet<>();
        s.add("tb_user");
        s.add("tb_menu");
        s.addAll(BASE_TABLES);
        s.add("tb_retention");
        return s;
    }

    static String tableOf(String st) {
        Matcher m = INSERT_TABLE.matcher(st.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static int countTables(List<String> stmts) {
        int n = 0;
        for (String s : stmts) if (s.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) n++;
        return n;
    }

    private static String q(String v) {
        return v == null ? "NULL" : "'" + v.replace("'", "''") + "'";
    }
}
