package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * DB 하나에 대한 Spring JDBC 연결 (DataSource + JdbcTemplate).
 * 주 DB(MyBatis 가 쓰는 DataSource)와 별개로 MySQL / MariaDB / Sybase / Oracle 을 각각 주입받아 쓸 수 있게 합니다.
 * 주 DB 와 같은 DB 면 그 풀을 함께 씀 (owned=false, 닫지 않음).
 */
public class NamedJdbc {

    private final String name;
    private final DataSource dataSource;
    private final boolean owned;
    private final String testQuery;
    private final JdbcTemplate jdbcTemplate;

    public NamedJdbc(String name, DataSource dataSource, boolean owned, String testQuery) {
        this.name = name;
        this.dataSource = dataSource;
        this.owned = owned;
        this.testQuery = testQuery;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public String getName() { return name; }
    public DataSource getDataSource() { return dataSource; }
    public JdbcTemplate getJdbcTemplate() { return jdbcTemplate; }
    public boolean isShared() { return !owned; }

    /** 연결 확인 : 제품·버전·URL·걸린 시간 (실패하면 error) */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        long t0 = System.currentTimeMillis();
        try {
            getJdbcTemplate().queryForObject(testQuery, Object.class);
            try (Connection c = getDataSource().getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                m.put("connected", true);
                m.put("product", md.getDatabaseProductName());
                m.put("version", md.getDatabaseProductVersion());
                m.put("url", md.getURL());
            }
        } catch (Exception e) {
            m.put("connected", false);
            m.put("error", rootMessage(e));
        }
        m.put("sharedWithPrimary", !owned);
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        return m;
    }

    public void close() {
        if (owned && dataSource instanceof HikariDataSource) ((HikariDataSource) dataSource).close();
    }

    static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
