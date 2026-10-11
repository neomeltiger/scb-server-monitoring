package com.scb.monitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Sybase ASE 연결 (sybase 프로필, 보조 DB).
 * 주 DB(MySQL, spring.datasource)의 자동 설정과 충돌하지 않도록 DataSource / DataSourceProperties 를
 * 빈으로 노출하지 않고 {@link SybaseJdbc} 로 감싸서 등록합니다. 접속 정보: app.sybase.datasource.*
 * DB 가 꺼져 있어도 애플리케이션은 시작되며, 상태는 /api/db/status 로 확인합니다.
 */
@Configuration
@Profile("sybase")
public class SybaseDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.sybase.datasource")
    public SybaseSettings sybaseSettings() {
        return new SybaseSettings();
    }

    @Bean(destroyMethod = "close")
    public SybaseJdbc sybaseJdbc(SybaseSettings s) {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName(s.getDriverClassName());
        ds.setJdbcUrl(s.getUrl());
        ds.setUsername(s.getUsername());
        ds.setPassword(s.getPassword());
        ds.setPoolName(s.getHikari().getPoolName());
        ds.setMaximumPoolSize(s.getHikari().getMaximumPoolSize());
        ds.setMinimumIdle(s.getHikari().getMinimumIdle());
        ds.setConnectionTimeout(s.getHikari().getConnectionTimeout());
        ds.setConnectionTestQuery(s.getHikari().getConnectionTestQuery());   // jTDS 는 isValid() 미지원
        ds.setInitializationFailTimeout(s.getHikari().getInitializationFailTimeout());
        return new SybaseJdbc(ds);
    }

    /** app.sybase.datasource.* */
    public static class SybaseSettings {
        private String driverClassName = "net.sourceforge.jtds.jdbc.Driver";
        private String url;
        private String username;
        private String password;
        private Hikari hikari = new Hikari();

        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Hikari getHikari() { return hikari; }
        public void setHikari(Hikari hikari) { this.hikari = hikari; }
    }

    /** app.sybase.datasource.hikari.* */
    public static class Hikari {
        private String poolName = "sybase-pool";
        private int maximumPoolSize = 5;
        private int minimumIdle = 1;
        private long connectionTimeout = 5000;
        private String connectionTestQuery = "select 1";
        private long initializationFailTimeout = -1;

        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public String getConnectionTestQuery() { return connectionTestQuery; }
        public void setConnectionTestQuery(String connectionTestQuery) { this.connectionTestQuery = connectionTestQuery; }
        public long getInitializationFailTimeout() { return initializationFailTimeout; }
        public void setInitializationFailTimeout(long initializationFailTimeout) { this.initializationFailTimeout = initializationFailTimeout; }
    }
}
