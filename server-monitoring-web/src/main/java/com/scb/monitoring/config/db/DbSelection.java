package com.scb.monitoring.config.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 커넥션 풀 만들기 (주 DB {@link DbManager} 와 개별 연결 MysqlJdbc / MariaDbJdbc / H2Jdbc 공통).
 * 주 DB 선택·자동 교체는 {@link DbManager}.
 */
public final class DbSelection {

    private static final Logger log = LoggerFactory.getLogger(DbSelection.class);

    private DbSelection() {
    }

    static HikariDataSource mysqlPool(DbProperties p) {
        DbProperties.Conn c = p.getMysql();
        return pool("mysql-pool", DbKind.MYSQL.driver(), c.getMaximumPoolSize(), c.getConnectionTimeout(),
                c.getUrl(), c.getUsername(), c.getPassword());
    }

    static HikariDataSource h2Pool(H2Db h2) {
        h2.prepare();
        HikariDataSource ds = h2.pool("h2-pool");
        H2Db.ensureSchema(ds);
        log.info("DB 연결 : H2 {}", h2.jdbcUrl());
        return ds;
    }

    static HikariDataSource pool(String name, String driver, int maxPool, long connectionTimeout, String url, String user, String pw) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setDriverClassName(driver);
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(pw);
        ds.setMaximumPoolSize(maxPool);
        ds.setConnectionTimeout(connectionTimeout);
        return ds;
    }
}
