package com.scb.monitoring.config;

import com.scb.monitoring.config.db.NamedJdbc;
import com.zaxxer.hikari.HikariDataSource;

/** Sybase 연결 (sybase 프로필). 주 DB(MySQL / MariaDB)와 구분하기 위해 DataSource 대신 이 타입으로 주입합니다. */
public class SybaseJdbc extends NamedJdbc {

    public SybaseJdbc(HikariDataSource dataSource) {
        super("Sybase", dataSource, true, "select 1");   // jTDS 는 JDBC4 isValid() 미지원 → 검사 쿼리
    }
}
