package com.scb.monitoring.config.db;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MariaDB 개별 연결 (app.db.mariadb.*). 주 DB 가 MariaDB 이면 그 풀을 함께 씀.
 * 임베디드 MariaDB 를 쓰는 설정이면 처음 쓸 때 시작합니다 (주 DB 가 MySQL 이어도 데이터 이전 등에 사용).
 */
public class MariaDbJdbc extends NamedJdbc {

    private final EmbeddedMariaDb embedded;

    public MariaDbJdbc(DataSource dataSource, boolean owned, EmbeddedMariaDb embedded) {
        super(embedded != null ? "MariaDB (임베디드)" : "MariaDB", dataSource, owned, "SELECT 1");
        this.embedded = embedded;
    }

    /** 임베디드면 실행 중인지 확인하고 없으면 시작 */
    public void ensureStarted() {
        if (embedded != null) embedded.ensureStarted();
    }

    @Override
    public JdbcTemplate getJdbcTemplate() {
        ensureStarted();
        return super.getJdbcTemplate();
    }

    /** 상태 확인만으로 임베디드 MariaDB 를 띄우지는 않음 */
    @Override
    public java.util.Map<String, Object> status() {
        if (embedded != null && !embedded.portOpen()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", getName());
            m.put("connected", false);
            m.put("running", false);
            m.put("available", embedded.isAvailable());
            m.put("home", embedded.getHome().getAbsolutePath());
            m.put("data", embedded.getData().getAbsolutePath());
            m.put("port", embedded.getPort());
            m.put("error", embedded.isAvailable() ? "실행 중 아님 (주 DB 가 MySQL 이면 필요할 때만 시작)" : "MariaDB 프로그램 없음 (mariadb/" + EmbeddedMariaDb.platform() + ")");
            return m;
        }
        java.util.Map<String, Object> m = super.status();
        if (embedded != null) {
            m.put("running", true);
            m.put("startedByWeb", embedded.isStartedByUs());
            m.put("data", embedded.getData().getAbsolutePath());
        }
        return m;
    }

    public EmbeddedMariaDb getEmbedded() { return embedded; }
}
