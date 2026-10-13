package com.scb.monitoring.config.db;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.zaxxer.hikari.HikariDataSource;

/**
 * H2 임베디드 파일 DB : 자바만으로 동작해 Windows · Solaris 10 SPARC 어디서나 사용 (MariaDB 프로그램이 없는 Solaris 의 대체 DB).
 * <ul>
 *   <li>파일 : {dir}/scb_mon_agent.mv.db (기본 dir = 실행 폴더의 data/h2, 환경변수 SCB_H2_DATA)</li>
 *   <li>스키마 : db/schema-ansi.sql (자동 테스트가 매퍼 전체를 검증하는 것과 같은 DDL·모드) + data.sql + users.sql</li>
 *   <li>한 프로세스만 열 수 있음 (같은 파일을 쓰는 웹을 두 개 띄우지 말 것)</li>
 * </ul>
 */
public class H2Db {

    private static final Logger log = LoggerFactory.getLogger(H2Db.class);
    public static final String NAME = "scb_mon_agent";

    private final DbProperties.H2Conf cfg;
    private final File dir;

    public H2Db(DbProperties.H2Conf cfg) {
        this.cfg = cfg;
        this.dir = cfg.getDir() != null && !cfg.getDir().trim().isEmpty() ? new File(cfg.getDir().trim())
                : new File(new File(System.getProperty("user.dir", "."), "data"), "h2");
    }

    public boolean isEnabled() { return cfg.isEnabled(); }
    public File getDir() { return dir; }

    /** DB 파일이 이미 있는지 */
    public boolean exists() {
        return new File(dir, NAME + ".mv.db").isFile();
    }

    public String jdbcUrl() {
        String path = new File(dir, NAME).getAbsolutePath().replace('\\', '/');
        return "jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    /** 데이터 폴더 만들기 (실제로 쓸 때만) */
    public void prepare() {
        if (!dir.isDirectory()) dir.mkdirs();
    }

    public HikariDataSource pool(String poolName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(poolName);
        ds.setDriverClassName("org.h2.Driver");
        ds.setJdbcUrl(jdbcUrl());
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMaximumPoolSize(cfg.getMaximumPoolSize());
        return ds;
    }

    /** 테이블이 없으면 schema-ansi.sql + data.sql + users.sql 실행 */
    public static void ensureSchema(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            int tables;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND UPPER(TABLE_NAME) = 'TB_SERVER'")) {
                rs.next();
                tables = rs.getInt(1);
            }
            if (tables > 0) return;
            log.info("H2 : {} 스키마 생성 (db/schema-ansi.sql, data.sql, users.sql)", NAME);
            for (String script : new String[] {"db/schema-ansi.sql", "db/data.sql", "db/users.sql"}) {
                ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(script), StandardCharsets.UTF_8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 스키마 확인 실패 : " + e.getMessage(), e);
        }
    }
}
