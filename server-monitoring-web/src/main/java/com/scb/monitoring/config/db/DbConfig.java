package com.scb.monitoring.config.db;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * DB 연결 구성 (Spring Boot JDBC).
 * <ul>
 *   <li>주 DB (MyBatis / JdbcTemplate 기본) : {@link DbManager} 의 {@link SwitchingDataSource}
 *       - 우선순위 Oracle → Sybase → MySQL → MariaDB → H2, 연결이 끊기면 다음 순위로 자동 교체,
 *       설정은 conf/scb-db.properties ({@link DbSettingsStore}, 없으면 SCB_DB 등 기존 환경변수), 관리자 화면 /admin/db</li>
 *   <li>개별 연결 (각각 주입받아 사용) : {@link MysqlJdbc}, {@link MariaDbJdbc}, {@link H2Jdbc},
 *       Sybase({@link com.scb.monitoring.config.SybaseJdbc}, sybase 프로필), Oracle({@link OracleJdbc}, oracle 프로필)
 *       - 주 DB 와 별개 풀, 처음 쓸 때 연결 (시작할 때 연결하지 않음)</li>
 * </ul>
 * spring.datasource.* 는 쓰지 않습니다 (이 클래스가 DataSource 를 만들면 Spring Boot 자동 설정은 물러남).
 */
@Configuration
public class DbConfig {

    @Bean(destroyMethod = "stop")
    public EmbeddedMariaDb embeddedMariaDb(DbProperties p) {
        return new EmbeddedMariaDb(p.getEmbedded(), p.getMariadb().getPassword());
    }

    @Bean
    public H2Db h2Db(DbProperties p) {
        return new H2Db(p.getH2());
    }

    /** conf/scb-db.properties (SCB_DB_CONFIG 로 위치 지정) */
    @Bean
    public DbSettingsStore dbSettingsStore(DbProperties p, @Value("${app.db.config-file:${SCB_DB_CONFIG:}}") String path) {
        return new DbSettingsStore(p, path);
    }

    @Bean
    public DbManager dbManager(DbSettingsStore store, EmbeddedMariaDb embedded, H2Db h2) {
        return new DbManager(store, embedded.isEnabled() ? embedded : null, h2).start();
    }

    /** 주 DB : MyBatis 매퍼, 트랜잭션, 기본 JdbcTemplate 이 사용 (스키마 준비·구조 보완은 DbManager 가 DB 를 열 때) */
    @Bean
    @Primary
    public DataSource dataSource(DbManager manager) {
        return manager.getDataSource();
    }

    @Bean(destroyMethod = "close")
    public MysqlJdbc mysqlJdbc(DbProperties p) {
        return new MysqlJdbc(lazy(DbSelection.mysqlPool(p)), true);
    }

    @Bean(destroyMethod = "close")
    public MariaDbJdbc mariaDbJdbc(DbProperties p, EmbeddedMariaDb embedded) {
        EmbeddedMariaDb e = embedded.isEnabled() ? embedded : null;
        HikariDataSource ds;
        if (e != null) {
            // 비밀번호는 처음 연결할 때 읽음 (MariaDB 를 쓰지 않으면 비밀번호 파일도 만들지 않음)
            ds = new HikariDataSource() {
                @Override
                public String getPassword() { return e.getPassword(); }
            };
            ds.setPoolName("mariadb-jdbc");
            ds.setDriverClassName(DbKind.MARIADB.driver());
            ds.setJdbcUrl(e.jdbcUrl());
            ds.setUsername("root");
            ds.setMaximumPoolSize(p.getMariadb().getMaximumPoolSize());
            ds.setConnectionTimeout(p.getMariadb().getConnectionTimeout());
        } else {
            ds = DbSelection.pool("mariadb-jdbc", DbKind.MARIADB.driver(), p.getMariadb().getMaximumPoolSize(),
                    p.getMariadb().getConnectionTimeout(), p.getMariadb().getUrl(), p.getMariadb().getUsername(), p.getMariadb().getPassword());
        }
        return new MariaDbJdbc(lazy(ds), true, e);
    }

    @Bean(destroyMethod = "close")
    public H2Jdbc h2Jdbc(H2Db h2) {
        return new H2Jdbc(lazy(h2.pool("h2-jdbc")), true, h2);
    }

    /** 시작할 때 연결하지 않음 (DB 가 꺼져 있어도 애플리케이션은 시작) */
    private static HikariDataSource lazy(HikariDataSource ds) {
        ds.setMinimumIdle(0);
        ds.setInitializationFailTimeout(-1);
        if (ds.getPoolName() != null && !ds.getPoolName().endsWith("-jdbc")) ds.setPoolName(ds.getPoolName().replace("-pool", "-jdbc"));
        return ds;
    }
}
