package com.scb.monitoring.config.db;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * DB 데이터 이전 명령 : --app.db.migrate=원본:대상 (mysql / mariadb / h2) 로 실행하면 복사하고 끝냄.
 * <pre>
 *   java -jar scb_monitor_web.jar --spring.main.web-application-type=none --app.db.migrate=mysql:mariadb
 *   (실행 패키지 : bin\scb_web.bat migrate-db mysql:mariadb , bin/scb_web.sh migrate-db mysql:h2)
 * </pre>
 * 웹 화면·스케줄러는 띄우지 않으며 (SchedulingConfig), 대상 테이블 내용은 원본으로 바뀝니다.
 */
@Component
@ConditionalOnProperty("app.db.migrate")
public class DbMigrateRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbMigrateRunner.class);

    private final Environment env;
    private final MysqlJdbc mysql;
    private final MariaDbJdbc mariadb;
    private final H2Jdbc h2;
    private final ConfigurableApplicationContext ctx;

    public DbMigrateRunner(Environment env, MysqlJdbc mysql, MariaDbJdbc mariadb, H2Jdbc h2, ConfigurableApplicationContext ctx) {
        this.env = env;
        this.mysql = mysql;
        this.mariadb = mariadb;
        this.h2 = h2;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String spec = env.getProperty("app.db.migrate", "").trim().toLowerCase(Locale.ROOT);
        int code;
        try {
            String[] p = spec.split("[:>,]");
            if (p.length != 2 || p[0].equals(p[1])) throw new IllegalArgumentException("형식 : 원본:대상 (mysql / mariadb / h2) - 받은 값 " + spec);
            NamedJdbc from = pick(p[0]);
            NamedJdbc to = pick(p[1]);
            log.info("DB 이전 시작 : {} → {}", from.getName(), to.getName());
            List<DbCopy.Result> rs = DbCopy.copy(from.getDataSource(), to.getDataSource());
            rs.forEach(r -> log.info("  {}", r));
            log.info("DB 이전 끝 : {}", DbCopy.summary(rs));
            code = rs.stream().allMatch(DbCopy.Result::ok) ? 0 : 2;
        } catch (Exception e) {
            log.error("DB 이전 실패 : {}", NamedJdbc.rootMessage(e), e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(ctx, () -> exit));
    }

    private NamedJdbc pick(String name) {
        switch (name) {
            case DbProperties.MYSQL: return mysql;
            case DbProperties.MARIADB:
                mariadb.ensureStarted();          // 임베디드면 시작 (처음이면 스키마 생성)
                return mariadb;
            case DbProperties.H2:
                h2.ensureSchema();
                return h2;
            default: throw new IllegalArgumentException("알 수 없는 DB : " + name + " (mysql / mariadb / h2)");
        }
    }
}
