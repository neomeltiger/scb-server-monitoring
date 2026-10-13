package com.scb.monitoring.config.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Oracle 보조 연결 (oracle 프로필, ojdbc8). Sybase 와 같은 방식 - 주 DB 와 별개로 {@link OracleJdbc} 를 주입받아 사용.
 * 접속 정보 : app.oracle.datasource.* (환경변수 SCB_ORACLE_URL / SCB_ORACLE_USER / SCB_ORACLE_PASSWORD).
 * DB 가 꺼져 있어도 애플리케이션은 시작되며 상태는 /api/db/status 로 확인.
 */
@Configuration
@Profile("oracle")
public class OracleDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.oracle.datasource")
    public OracleSettings oracleSettings() {
        return new OracleSettings();
    }

    @Bean(destroyMethod = "close")
    public OracleJdbc oracleJdbc(OracleSettings s) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("oracle-pool");
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        ds.setJdbcUrl(s.getUrl());
        ds.setUsername(s.getUsername());
        ds.setPassword(s.getPassword());
        ds.setMaximumPoolSize(s.getMaximumPoolSize());
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(s.getConnectionTimeout());
        ds.setConnectionTestQuery("SELECT 1 FROM DUAL");
        ds.setInitializationFailTimeout(-1);
        return new OracleJdbc(ds);
    }

    /** app.oracle.datasource.* */
    public static class OracleSettings {
        /** 예) jdbc:oracle:thin:@//10.0.0.20:1521/ORCLPDB1 */
        private String url;
        private String username;
        private String password;
        private int maximumPoolSize = 5;
        private long connectionTimeout = 5000;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }
}
