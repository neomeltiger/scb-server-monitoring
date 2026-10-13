package com.scb.monitoring.config.db;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 주 DB 로 쓸 수 있는 DB 종류. 선언 순서가 기본 우선순위 (Oracle → Sybase → MySQL → MariaDB → H2).
 * 우선순위는 conf/scb-db.properties 의 db.order 로 바꿀 수 있음 ({@link DbSettings}).
 */
public enum DbKind {

    ORACLE("oracle", "Oracle", "oracle.jdbc.OracleDriver", "SELECT 1 FROM DUAL",
            "jdbc:oracle:thin:@//127.0.0.1:1521/ORCLPDB1"),
    /** jTDS : TEXT 를 문자열로 받도록 useLOBs=false */
    SYBASE("sybase", "Sybase ASE", "net.sourceforge.jtds.jdbc.Driver", "select 1",
            "jdbc:jtds:sybase://127.0.0.1:5000/scbmon;charset=utf8;useLOBs=false;appName=server-monitoring-web"),
    MYSQL("mysql", "MySQL", "com.mysql.cj.jdbc.Driver", "SELECT 1",
            "jdbc:mysql://127.0.0.1:13306/scb_mon_agent?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"),
    MARIADB("mariadb", "MariaDB", "org.mariadb.jdbc.Driver", "SELECT 1",
            "jdbc:mariadb://127.0.0.1:3306/scb_mon_agent"),
    H2("h2", "H2", "org.h2.Driver", "SELECT 1", null);

    public static final List<DbKind> DEFAULT_ORDER = Collections.unmodifiableList(Arrays.asList(values()));

    private final String key;
    private final String label;
    private final String driver;
    private final String testQuery;
    private final String sampleUrl;

    DbKind(String key, String label, String driver, String testQuery, String sampleUrl) {
        this.key = key;
        this.label = label;
        this.driver = driver;
        this.testQuery = testQuery;
        this.sampleUrl = sampleUrl;
    }

    /** 이름(oracle / sybase / mysql / mariadb / h2, 대소문자 무관) → 종류. 모르는 이름은 null */
    public static DbKind of(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (DbKind d : values()) if (d.key.equals(k)) return d;
        return null;
    }

    /** MySQL 문법 스키마(schema-mysql.sql)를 쓰는 DB */
    public boolean mysqlFamily() {
        return this == MYSQL || this == MARIADB;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String driver() { return driver; }
    public String testQuery() { return testQuery; }
    public String sampleUrl() { return sampleUrl; }
}
