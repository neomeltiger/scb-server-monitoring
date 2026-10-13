package com.scb.monitoring.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 (서버 장애 알람 점검, 하트비트 감시 등).
 * DB 데이터 이전 명령(--app.db.migrate) 으로 실행할 때는 끔 - 이전하는 동안 DB 에 쓰지 않도록.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${app.scheduling.enabled:true}' == 'true' and '${app.db.migrate:}' == ''")
public class SchedulingConfig {
}
