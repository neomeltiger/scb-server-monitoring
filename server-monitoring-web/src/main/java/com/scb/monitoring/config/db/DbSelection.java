package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 주 DB 선택 결과 (MyBatis·화면이 쓰는 DataSource).
 * <ul>
 *   <li>auto (기본) : MySQL 연결 확인(probeTimeoutMs) → 안 되면 MariaDB (임베디드면 시작, 프로그램이 없으면 건너뜀)
 *       → 그래도 안 되면 H2 파일 DB. Solaris 10 SPARC 는 MariaDB 프로그램이 없으므로 MySQL → H2</li>
 *   <li>mysql / mariadb / h2 : 그 DB 만 (연결 안 되면 시작 실패)</li>
 * </ul>
 */
public class DbSelection {

    private static final Logger log = LoggerFactory.getLogger(DbSelection.class);

    private final String db;              // mysql / mariadb / h2
    private final String mode;
    private final String reason;
    private final HikariDataSource dataSource;

    private DbSelection(String db, String mode, String reason, HikariDataSource dataSource) {
        this.db = db;
        this.mode = mode;
        this.reason = reason;
        this.dataSource = dataSource;
    }

    public static DbSelection choose(DbProperties p, EmbeddedMariaDb embedded, H2Db h2) {
        String mode = p.mode();
        switch (mode) {
            case DbProperties.MYSQL:
                log.info("DB 주 연결 : MySQL (SCB_DB=mysql) {}", p.getMysql().getUrl());
                return new DbSelection(DbProperties.MYSQL, mode, "SCB_DB=mysql", mysqlPool(p));
            case DbProperties.MARIADB:
                return new DbSelection(DbProperties.MARIADB, mode, "SCB_DB=mariadb", mariaPool(p, embedded));
            case DbProperties.H2:
                return new DbSelection(DbProperties.H2, mode, "SCB_DB=h2", h2Pool(h2));
            default:
                return auto(p, embedded, h2);
        }
    }

    /** MySQL → MariaDB → H2 */
    private static DbSelection auto(DbProperties p, EmbeddedMariaDb embedded, H2Db h2) {
        List<String> why = new ArrayList<>();
        String fail = probe(p.getMysql().getUrl(), p.getMysql().getUsername(), p.getMysql().getPassword(), p.getProbeTimeoutMs());
        if (fail == null) {
            log.info("DB 주 연결 : MySQL (SCB_DB=auto, 연결 확인됨) {}", p.getMysql().getUrl());
            return new DbSelection(DbProperties.MYSQL, DbProperties.AUTO, "auto - MySQL 연결됨", mysqlPool(p));
        }
        why.add("MySQL " + fail);
        log.warn("DB : MySQL 연결 안 됨 ({}) → MariaDB 시도", fail);

        if (embedded != null && embedded.isEnabled()) {
            if (embedded.isAvailable() || embedded.portOpen()) {
                try {
                    HikariDataSource ds = mariaPool(p, embedded);
                    return new DbSelection(DbProperties.MARIADB, DbProperties.AUTO, "auto - " + String.join(" / ", why) + " → MariaDB 임베디드", ds);
                } catch (RuntimeException e) {
                    why.add("MariaDB " + NamedJdbc.rootMessage(e));
                    log.warn("DB : 임베디드 MariaDB 사용 불가 ({}) → H2", NamedJdbc.rootMessage(e));
                }
            } else {
                why.add("MariaDB 프로그램 없음 (mariadb/" + EmbeddedMariaDb.platform() + ")");
                log.info("DB : 이 OS 용 MariaDB 프로그램 없음 ({}) → H2", embedded.getHome().getAbsolutePath());
            }
        } else if (p.getMariadb().getUrl() != null && !p.getMariadb().getUrl().trim().isEmpty()) {
            DbProperties.Conn m = p.getMariadb();
            String mf = probe(m.getUrl(), m.getUsername(), m.getPassword(), p.getProbeTimeoutMs());
            if (mf == null) {
                return new DbSelection(DbProperties.MARIADB, DbProperties.AUTO, "auto - " + String.join(" / ", why) + " → MariaDB", mariaPool(p, null));
            }
            why.add("MariaDB " + mf);
        }

        if (h2 != null && h2.isEnabled()) {
            log.warn("DB 주 연결 : H2 파일 DB {} ({})", h2.getDir().getAbsolutePath(), String.join(" / ", why));
            return new DbSelection(DbProperties.H2, DbProperties.AUTO, "auto - " + String.join(" / ", why) + " → H2", h2Pool(h2));
        }
        throw new IllegalStateException("사용할 수 있는 DB 가 없습니다 : " + String.join(" / ", why));
    }

    /** 연결 확인 : 실패 사유, 되면 null */
    static String probe(String url, String user, String password, int timeoutMs) {
        if (url == null || url.trim().isEmpty()) return "URL 없음";
        String u = url + (url.contains("?") ? "&" : "?") + "connectTimeout=" + timeoutMs + "&socketTimeout=" + timeoutMs;
        Properties props = new Properties();
        if (user != null) props.setProperty("user", user);
        if (password != null) props.setProperty("password", password);
        try (Connection con = DriverManager.getConnection(u, props)) {
            return con.isValid(Math.max(1, timeoutMs / 1000)) ? null : "연결 확인 실패";
        } catch (Exception e) {
            return NamedJdbc.rootMessage(e);
        }
    }

    static HikariDataSource mysqlPool(DbProperties p) {
        return pool("mysql-pool", "com.mysql.cj.jdbc.Driver", p.getMysql(), p.getMysql().getUrl(),
                p.getMysql().getUsername(), p.getMysql().getPassword());
    }

    static HikariDataSource mariaPool(DbProperties p, EmbeddedMariaDb embedded) {
        DbProperties.Conn c = p.getMariadb();
        if (embedded != null && embedded.isEnabled()) {
            embedded.ensureStarted();
            log.info("DB 주 연결 : MariaDB 임베디드 {} (data {})", embedded.jdbcUrl(), embedded.getData().getAbsolutePath());
            return pool("mariadb-pool", "org.mariadb.jdbc.Driver", c, embedded.jdbcUrl(), "root", embedded.getPassword());
        }
        log.info("DB 주 연결 : MariaDB {}", c.getUrl());
        return pool("mariadb-pool", "org.mariadb.jdbc.Driver", c, c.getUrl(), c.getUsername(), c.getPassword());
    }

    static HikariDataSource h2Pool(H2Db h2) {
        h2.prepare();
        HikariDataSource ds = h2.pool("h2-pool");
        H2Db.ensureSchema(ds);
        log.info("DB 주 연결 : H2 {}", h2.jdbcUrl());
        return ds;
    }

    static HikariDataSource pool(String name, String driver, DbProperties.Conn c, String url, String user, String pw) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setDriverClassName(driver);
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(pw);
        ds.setMaximumPoolSize(c.getMaximumPoolSize());
        ds.setConnectionTimeout(c.getConnectionTimeout());
        return ds;
    }

    public String getDb() { return db; }
    public String getMode() { return mode; }
    public String getReason() { return reason; }
    public DataSource getDataSource() { return dataSource; }
    public boolean is(String name) { return db.equals(name); }
}
