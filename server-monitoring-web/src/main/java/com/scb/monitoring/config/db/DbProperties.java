package com.scb.monitoring.config.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주 DB 선택과 MySQL / MariaDB 접속 정보 (app.db.*).
 * <ul>
 *   <li>primary : auto(기본) = MySQL → (안 되면) MariaDB → (안 되면) H2 / mysql / mariadb / h2 = 그 DB 만
 *       - 환경변수 SCB_DB (배포 패키지 conf/scb_web.env)</li>
 *   <li>h2 : 자바만으로 도는 파일 DB (Windows · Solaris 10 SPARC 모두) - MariaDB 프로그램이 없는 Solaris 의 대체 DB</li>
 *   <li>mariadb.embedded : 웹이 직접 띄우는 MariaDB (mariadb/&lt;플랫폼&gt;/bin/mariadbd, 127.0.0.1 전용)</li>
 * </ul>
 */
@ConfigurationProperties("app.db")
public class DbProperties {

    public static final String AUTO = "auto";
    public static final String MYSQL = "mysql";
    public static final String MARIADB = "mariadb";
    public static final String H2 = "h2";

    private String primary = AUTO;
    /** auto 일 때 MySQL 연결 확인 제한 시간 (ms) */
    private int probeTimeoutMs = 3000;
    private Conn mysql = new Conn();
    private Conn mariadb = new Conn();
    private Embedded embedded = new Embedded();
    private H2Conf h2 = new H2Conf();

    public String mode() {
        String p = primary == null ? AUTO : primary.trim().toLowerCase();
        return MYSQL.equals(p) || MARIADB.equals(p) || H2.equals(p) ? p : AUTO;
    }

    public String getPrimary() { return primary; }
    public void setPrimary(String primary) { this.primary = primary; }
    public int getProbeTimeoutMs() { return probeTimeoutMs; }
    public void setProbeTimeoutMs(int probeTimeoutMs) { this.probeTimeoutMs = probeTimeoutMs; }
    public Conn getMysql() { return mysql; }
    public void setMysql(Conn mysql) { this.mysql = mysql; }
    public Conn getMariadb() { return mariadb; }
    public void setMariadb(Conn mariadb) { this.mariadb = mariadb; }
    public Embedded getEmbedded() { return embedded; }
    public void setEmbedded(Embedded embedded) { this.embedded = embedded; }
    public H2Conf getH2() { return h2; }
    public void setH2(H2Conf h2) { this.h2 = h2; }

    /** H2 파일 DB (app.db.h2.*) */
    public static class H2Conf {
        private boolean enabled = true;
        /** 데이터 폴더. 비우면 실행 폴더의 data/h2 */
        private String dir;
        private int maximumPoolSize = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
    }

    /** 접속 정보 + 커넥션 풀 */
    public static class Conn {
        private String url;
        private String username;
        private String password;
        private int maximumPoolSize = 10;
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

    /** 임베디드 MariaDB (app.db.embedded.*) */
    public static class Embedded {
        private boolean enabled = true;
        /** MariaDB 설치 폴더 (bin/mariadbd). 비우면 실행 폴더의 mariadb/&lt;플랫폼&gt; 을 찾음 */
        private String home;
        /** 데이터 폴더. 비우면 실행 폴더의 data/mariadb */
        private String data;
        private int port = 13307;
        private int startTimeoutSec = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHome() { return home; }
        public void setHome(String home) { this.home = home; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getStartTimeoutSec() { return startTimeoutSec; }
        public void setStartTimeoutSec(int startTimeoutSec) { this.startTimeoutSec = startTimeoutSec; }
    }
}
