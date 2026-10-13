package com.scb.monitoring.config.db;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 파일 DB 개별 연결. 주 DB 가 H2 면 그 풀을 함께 씀.
 * 주 DB 가 아니면 처음 쓸 때 열고 스키마를 만듦 (데이터 이전 대상 등) - H2 파일은 한 프로세스만 열 수 있음.
 */
public class H2Jdbc extends NamedJdbc {

    private final H2Db h2;
    private volatile boolean schemaChecked;

    public H2Jdbc(DataSource dataSource, boolean owned, H2Db h2) {
        super("H2 (파일 DB)", dataSource, owned, "SELECT 1");
        this.h2 = h2;
        this.schemaChecked = !owned;      // 주 DB 면 시작할 때 이미 확인
    }

    @Override
    public JdbcTemplate getJdbcTemplate() {
        ensureSchema();
        return super.getJdbcTemplate();
    }

    public void ensureSchema() {
        if (!schemaChecked) {
            h2.prepare();
            H2Db.ensureSchema(getDataSource());
            schemaChecked = true;
        }
    }

    /** 주 DB 가 아니고 파일도 아직 없으면 상태 확인만으로 만들지 않음 */
    @Override
    public Map<String, Object> status() {
        if (!isShared() && !h2.exists()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", getName());
            m.put("connected", false);
            m.put("exists", false);
            m.put("dir", h2.getDir().getAbsolutePath());
            m.put("error", "아직 만들지 않음 (주 DB 가 H2 이거나 데이터 이전 대상일 때 만듦)");
            return m;
        }
        if (!isShared()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", getName());
            m.put("connected", false);
            m.put("exists", true);
            m.put("dir", h2.getDir().getAbsolutePath());
            m.put("error", "주 DB 가 아니어서 열지 않음 (H2 파일은 한 프로세스만 열 수 있음)");
            return m;
        }
        Map<String, Object> m = super.status();
        m.put("dir", h2.getDir().getAbsolutePath());
        return m;
    }

    public H2Db getH2() { return h2; }
}
