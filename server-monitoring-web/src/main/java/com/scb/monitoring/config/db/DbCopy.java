package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 간 데이터 이전 (MySQL / MariaDB / H2 - 같은 테이블 구조).
 * 원본의 모든 테이블을 대상의 같은 이름 테이블로 복사합니다 (대상 테이블 내용은 지우고 넣음, 양쪽에 있는 컬럼만).
 * 외래키 검사는 복사하는 동안 끄고, 끝나면 테이블별 건수를 비교합니다. H2 대상은 IDENTITY 다음 값을 맞춤.
 */
public final class DbCopy {

    private static final Logger log = LoggerFactory.getLogger(DbCopy.class);
    private static final int BATCH = 500;

    private DbCopy() {
    }

    /** 테이블 1개 결과 */
    public static class Result {
        public final String table;
        public final long source;
        public long target;
        public String note;

        Result(String table, long source) {
            this.table = table;
            this.source = source;
        }

        public boolean ok() { return note == null && source == target; }

        @Override
        public String toString() {
            return String.format("%-26s 원본 %6d → 대상 %6d %s", table, source, target, ok() ? "OK" : (note != null ? note : "건수 다름"));
        }
    }

    public static List<Result> copy(DataSource source, DataSource target) throws SQLException {
        List<Result> out = new ArrayList<>();
        try (Connection src = source.getConnection(); Connection dst = target.getConnection()) {
            Map<String, String> srcTables = tables(src);
            Map<String, String> dstTables = tables(dst);
            boolean h2 = product(dst).contains("h2");
            dst.setAutoCommit(false);
            fkChecks(dst, false);
            try {
                for (Map.Entry<String, String> t : srcTables.entrySet()) {
                    String dstName = dstTables.get(t.getKey());
                    Result r = new Result(t.getValue(), count(src, t.getValue()));
                    out.add(r);
                    if (dstName == null) {
                        r.note = "대상에 테이블 없음 (건너뜀)";
                        log.warn("DB 이전 : {} 대상에 없음", t.getValue());
                        continue;
                    }
                    copyTable(src, t.getValue(), dst, dstName);
                    dst.commit();
                    r.target = count(dst, dstName);
                    if (h2) restartIdentities(dst, dstName);
                    log.info("DB 이전 : {}", r);
                }
                dst.commit();
            } catch (SQLException e) {
                dst.rollback();
                throw e;
            } finally {
                fkChecks(dst, true);
                dst.commit();
            }
        }
        return out;
    }

    /** 소문자 이름 → 실제 이름 (현재 DB 의 일반 테이블) */
    static Map<String, String> tables(Connection c) throws SQLException {
        Map<String, String> m = new TreeMap<>();
        DatabaseMetaData md = c.getMetaData();
        String schema = product(c).contains("h2") ? "PUBLIC" : null;
        try (ResultSet rs = md.getTables(c.getCatalog(), schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String n = rs.getString("TABLE_NAME");
                m.put(n.toLowerCase(Locale.ROOT), n);
            }
        }
        return m;
    }

    private static void copyTable(Connection src, String srcTable, Connection dst, String dstTable) throws SQLException {
        List<String> dstCols = new ArrayList<>();
        try (ResultSet rs = dst.getMetaData().getColumns(dst.getCatalog(), product(dst).contains("h2") ? "PUBLIC" : null, dstTable, "%")) {
            while (rs.next()) dstCols.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        try (Statement del = dst.createStatement()) {
            del.executeUpdate("DELETE FROM " + dstTable);
        }
        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + srcTable)) {
            ResultSetMetaData md = rs.getMetaData();
            List<Integer> idx = new ArrayList<>();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String n = md.getColumnLabel(i);
                if (dstCols.contains(n.toLowerCase(Locale.ROOT))) {
                    idx.add(i);
                    cols.add(n);
                }
            }
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(dstTable).append(" (").append(String.join(", ", cols)).append(") VALUES (");
            for (int i = 0; i < cols.size(); i++) sql.append(i == 0 ? "?" : ", ?");
            sql.append(')');
            try (PreparedStatement ps = dst.prepareStatement(sql.toString())) {
                int n = 0;
                while (rs.next()) {
                    for (int k = 0; k < idx.size(); k++) {
                        int i = idx.get(k);
                        int type = md.getColumnType(i);
                        if (type == Types.TIMESTAMP || type == Types.DATE && "DATETIME".equalsIgnoreCase(md.getColumnTypeName(i))) {
                            ps.setTimestamp(k + 1, rs.getTimestamp(i));
                        } else {
                            Object v = rs.getObject(i);
                            if (v == null) ps.setNull(k + 1, type);
                            else ps.setObject(k + 1, v);
                        }
                    }
                    ps.addBatch();
                    if (++n % BATCH == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void fkChecks(Connection c, boolean on) throws SQLException {
        String p = product(c);
        try (Statement st = c.createStatement()) {
            if (p.contains("h2")) st.execute("SET REFERENTIAL_INTEGRITY " + (on ? "TRUE" : "FALSE"));
            else if (p.contains("mysql") || p.contains("mariadb")) st.execute("SET FOREIGN_KEY_CHECKS = " + (on ? 1 : 0));
        }
    }

    /** H2 : 값을 직접 넣은 IDENTITY 컬럼의 다음 값을 최댓값 + 1 로 */
    private static void restartIdentities(Connection c, String table) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND IS_IDENTITY = 'YES'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        for (String col : ids) {
            long next;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(" + col + "), 0) + 1 FROM " + table)) {
                rs.next();
                next = rs.getLong(1);
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + table + " ALTER COLUMN " + col + " RESTART WITH " + next);
            }
        }
        c.commit();
    }

    static String product(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    }

    /** 결과 요약 (로그·화면용) */
    public static Map<String, Object> summary(List<Result> rs) {
        Map<String, Object> m = new LinkedHashMap<>();
        long ok = rs.stream().filter(Result::ok).count();
        m.put("tables", rs.size());
        m.put("ok", ok);
        m.put("rows", rs.stream().mapToLong(r -> r.target).sum());
        m.put("allOk", ok == rs.size());
        return m;
    }
}
