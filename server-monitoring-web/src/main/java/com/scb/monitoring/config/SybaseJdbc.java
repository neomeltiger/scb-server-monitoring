package com.scb.monitoring.config;

import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/** Sybase 연결 (sybase 프로필). 주 DB(MySQL)와 구분하기 위해 DataSource 대신 이 타입으로 주입합니다. */
public class SybaseJdbc {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public SybaseJdbc(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public JdbcTemplate getJdbcTemplate() { return jdbcTemplate; }

    public void close() { dataSource.close(); }
}
