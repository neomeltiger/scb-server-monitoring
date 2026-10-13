package com.scb.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * 통합 서버 관리 및 모니터링 시스템.
 * <ul>
 *   <li>화면: Thymeleaf (templates/), 공통 상단·좌측은 fragments/top.html, fragments/left.html</li>
 *   <li>로그인: Spring Security (config/SecurityConfig)</li>
 *   <li>웹 콘솔: WebSocket /ssh ↔ SSH (console/ 패키지)</li>
 *   <li>DB: 주 DB = MySQL 8 → (안 되면) MariaDB 임베디드 → H2 파일 DB (config.db.DbConfig, SCB_DB), MyBatis</li>
 *   <li>서버 장애 알람: 주기 점검 (alarm/ 패키지, 스케줄러는 config.SchedulingConfig)</li>
 *   <li>DB: Sybase ASE - sybase 프로필에서만 연결 (config/SybaseDataSourceConfig)</li>
 * </ul>
 * 실행: 외부 Tomcat 에 war 배포, 또는 java -jar server-monitoring-web.war
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MonitoringApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(MonitoringApplication.class, args);
    }

    /** 외부 Tomcat 배포 시 시작점 */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(MonitoringApplication.class);
    }
}
