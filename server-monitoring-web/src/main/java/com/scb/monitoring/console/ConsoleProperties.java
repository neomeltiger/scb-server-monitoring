package com.scb.monitoring.console;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 app.console.* (웹 콘솔 설정) */
@ConfigurationProperties(prefix = "app.console")
public class ConsoleProperties {

    /** live: 실제 SSH 접속 / demo: 브라우저 시뮬레이션 (WebSocket 미등록) */
    private String mode = "live";
    /** 접속 허용 서버 목록 파일 (IP, CIDR 대역, 호스트명) */
    private String allowedHostsFile = "config/allowed-hosts.json";
    /** 웹 콘솔 접속을 허용할 웹 주소. 비우면 같은 주소(same-origin)만 허용 */
    private List<String> allowedOrigins = new ArrayList<>();
    private Ssh ssh = new Ssh();

    public boolean isDemo() { return "demo".equalsIgnoreCase(mode); }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAllowedHostsFile() { return allowedHostsFile; }
    public void setAllowedHostsFile(String allowedHostsFile) { this.allowedHostsFile = allowedHostsFile; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    public Ssh getSsh() { return ssh; }
    public void setSsh(Ssh ssh) { this.ssh = ssh; }

    public static class Ssh {
        private int connectTimeoutMs = 15000;
        private int keepAliveMs = 30000;
        private int maxPasswordTries = 3;
        /** yes: known-hosts-file 에 등록된 서버만 접속 / no: 호스트 키 검사 안 함 */
        private String strictHostKeyChecking = "no";
        private String knownHostsFile = "";
        /**
         * 구형 SSH 서버(예: Solaris 10 Sun_SSH) 호환용 알고리즘 허용.
         * SHA-1 키 교환, ssh-rsa/ssh-dss 호스트 키, CBC 암호, hmac-sha1/md5 를 목록 끝에 추가합니다.
         * 최신 알고리즘이 우선이므로 최신 서버는 영향을 받지 않습니다.
         */
        private boolean legacyAlgorithms = true;
        /**
         * 터미널 종류(TERM). Solaris 10 등 구형 서버에는 xterm-256color 정의가 없어
         * vi, clear 등이 동작하지 않으므로 기본값은 xterm 입니다.
         */
        private String termType = "xterm";

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getKeepAliveMs() { return keepAliveMs; }
        public void setKeepAliveMs(int keepAliveMs) { this.keepAliveMs = keepAliveMs; }
        public int getMaxPasswordTries() { return maxPasswordTries; }
        public void setMaxPasswordTries(int maxPasswordTries) { this.maxPasswordTries = maxPasswordTries; }
        public String getStrictHostKeyChecking() { return strictHostKeyChecking; }
        public void setStrictHostKeyChecking(String strictHostKeyChecking) { this.strictHostKeyChecking = strictHostKeyChecking; }
        public String getKnownHostsFile() { return knownHostsFile; }
        public void setKnownHostsFile(String knownHostsFile) { this.knownHostsFile = knownHostsFile; }
        public boolean isLegacyAlgorithms() { return legacyAlgorithms; }
        public void setLegacyAlgorithms(boolean legacyAlgorithms) { this.legacyAlgorithms = legacyAlgorithms; }
        public String getTermType() { return termType; }
        public void setTermType(String termType) { this.termType = termType; }
    }
}
