package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 주 DB 구조 자동 보완 (웹 시작 시, 주 DB 를 고른 직후 - MySQL / MariaDB / H2 공통 SQL).
 * 이미 있으면 건드리지 않습니다.
 * <ul>
 *   <li>v11 : tb_user.pw_change_yn (임시 비밀번호 → 첫 로그인 변경), tb_user.menu_custom_yn (사용자별 메뉴 권한 사용),
 *       tb_user_menu (사용자별 허용 메뉴)</li>
 * </ul>
 * 처음 만드는 DB 는 schema-mysql.sql / schema-ansi.sql 에 이미 들어 있음.
 */
public final class SchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);

    private SchemaUpgrade() {
    }

    public static void apply(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            if (!tableExists(c, "tb_user")) return;          // 아직 스키마가 없는 DB (init 스크립트가 만듦)
            addColumn(c, "tb_user", "pw_change_yn", "CHAR(1) DEFAULT 'N' NOT NULL");
            addColumn(c, "tb_user", "menu_custom_yn", "CHAR(1) DEFAULT 'N' NOT NULL");
            if (!tableExists(c, "tb_user_menu")) {
                exec(c, "CREATE TABLE tb_user_menu ("
                        + " user_id VARCHAR(30) NOT NULL,"
                        + " menu_id VARCHAR(30) NOT NULL,"
                        + " reg_user VARCHAR(30),"
                        + " reg_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                        + " PRIMARY KEY (user_id, menu_id))");
                log.info("DB 구조 보완 : tb_user_menu 생성 (사용자별 메뉴 권한)");
            }
        } catch (SQLException e) {
            log.warn("DB 구조 보완 실패 : {}", e.getMessage());
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String t : new String[] {table, table.toUpperCase()}) {
            try (ResultSet rs = md.getTables(c.getCatalog(), null, t, new String[] {"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String t : new String[] {table, table.toUpperCase()}) {
            for (String col : new String[] {column, column.toUpperCase()}) {
                try (ResultSet rs = md.getColumns(c.getCatalog(), null, t, col)) {
                    if (rs.next()) return true;
                }
            }
        }
        return false;
    }

    private static void addColumn(Connection c, String table, String column, String def) throws SQLException {
        if (columnExists(c, table, column)) return;
        exec(c, "ALTER TABLE " + table + " ADD " + column + " " + def);
        log.info("DB 구조 보완 : {}.{} 추가", table, column);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
