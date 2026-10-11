package com.scb.monitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 app.security.* (로그인 사용자는 DB tb_user 에서 관리) */
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    /** 연속 로그인 실패 시 계정 잠금 횟수 */
    private int maxLoginFailures = 5;

    public int getMaxLoginFailures() { return maxLoginFailures; }
    public void setMaxLoginFailures(int maxLoginFailures) { this.maxLoginFailures = maxLoginFailures; }
}
