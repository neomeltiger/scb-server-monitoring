package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 주 DB 구조 자동 보완 (웹 시작 시 · 주 DB 를 바꿀 때). 이미 있으면 건드리지 않습니다.
 * <ul>
 *   <li>v10 : tb_menu (좌측 메뉴 · 메뉴 권한)</li>
 *   <li>v11 : tb_user.pw_change_yn (임시 비밀번호 → 첫 로그인 변경), tb_user.menu_custom_yn (사용자별 메뉴 권한 사용),
 *       tb_user_menu (사용자별 허용 메뉴)</li>
 *   <li>v12 : tb_server_report (서버 점검 리포트 - 웹 관리자 화면에서 요청할 때만)</li>
 * </ul>
 * 새로 만드는 테이블의 DDL 은 schema-ansi.sql 의 것을 DB 에 맞게 바꿔 씀 ({@link DbDialect#ddl}).
 * 처음 만드는 DB 는 schema-mysql.sql / schema-ansi.sql 에 이미 들어 있음.
 */
public final class SchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);

    private SchemaUpgrade() {
    }

    /** DB 종류는 연결에서 알아냄 */
    public static void apply(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            apply(c, DbSchema.kindOf(c));
        } catch (SQLException e) {
            log.warn("DB 구조 보완 실패 : {}", e.getMessage());
        }
    }

    public static void apply(DataSource ds, DbKind kind) {
        try (Connection c = ds.getConnection()) {
            apply(c, kind);
        } catch (SQLException e) {
            log.warn("DB 구조 보완 실패 : {}", e.getMessage());
        }
    }

    private static void apply(Connection c, DbKind kind) throws SQLException {
        if (!DbSchema.tableExists(c, kind, "tb_user")) return;          // 아직 스키마가 없는 DB (init 스크립트가 만듦)
        createTable(c, kind, "tb_menu", "좌측 메뉴");
        addColumn(c, kind, "tb_user", "pw_change_yn", "CHAR(1) DEFAULT 'N' NOT NULL");
        addColumn(c, kind, "tb_user", "menu_custom_yn", "CHAR(1) DEFAULT 'N' NOT NULL");
        createTable(c, kind, "tb_user_menu", "사용자별 메뉴 권한");
        createTable(c, kind, "tb_server_report", "서버 점검 리포트");
    }

    /** schema-ansi.sql 에 있는 그 테이블의 CREATE TABLE 을 DB 에 맞게 바꿔 실행 */
    private static void createTable(Connection c, DbKind kind, String table, String what) throws SQLException {
        if (DbSchema.tableExists(c, kind, table)) return;
        Pattern p = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+" + table + "\\s*\\(.*");
        for (String st : DbDialect.split(DbSchema.resource(DbSchema.ANSI))) {
            if (!p.matcher(st).matches()) continue;
            for (String ddl : DbDialect.ddl(st, kind)) exec(c, kind.mysqlFamily() ? ddl + " DEFAULT CHARSET=utf8mb4" : ddl);
            log.info("DB 구조 보완 : {} 생성 ({})", table, what);
            return;
        }
    }

    private static void addColumn(Connection c, DbKind kind, String table, String column, String def) throws SQLException {
        if (DbSchema.columnExists(c, kind, table, column)) return;
        exec(c, "ALTER TABLE " + table + " ADD " + column + " " + def);
        log.info("DB 구조 보완 : {}.{} 추가", table, column);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
        if (!c.getAutoCommit()) c.commit();
    }
}
