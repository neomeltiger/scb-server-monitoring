package com.scb.monitoring.config.db;

import com.zaxxer.hikari.HikariDataSource;

/** Oracle 보조 연결 (oracle 프로필, app.oracle.datasource.*). 모니터링 데이터는 주 DB(MySQL / MariaDB)에 저장 */
public class OracleJdbc extends NamedJdbc {

    public OracleJdbc(HikariDataSource dataSource) {
        super("Oracle", dataSource, true, "SELECT 1 FROM DUAL");
    }
}
