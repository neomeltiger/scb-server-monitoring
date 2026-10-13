package com.scb.monitoring.config.db;

import javax.sql.DataSource;

/** MySQL 8 개별 연결 (app.db.mysql.*). 주 DB 가 MySQL 이면 그 풀을 함께 씀 */
public class MysqlJdbc extends NamedJdbc {

    public MysqlJdbc(DataSource dataSource, boolean owned) {
        super("MySQL", dataSource, owned, "SELECT 1");
    }
}
