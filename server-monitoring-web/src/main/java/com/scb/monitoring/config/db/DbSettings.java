package com.scb.monitoring.config.db;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 주 DB 선택·자동 교체 설정 + DB 별 접속 정보 (conf/scb-db.properties ← {@link DbSettingsStore}).
 * <ul>
 *   <li>primary : auto = 우선순위(order) 대로 연결되는 첫 DB / oracle · sybase · mysql · mariadb · h2 = 그 DB 로 시작</li>
 *   <li>failover : 주 DB 연결이 끊기면 우선순위가 낮은 다음 DB 로 자동 교체 (다시 올라가는 것은 관리자 화면에서)</li>
 * </ul>
 */
public class DbSettings {

    public static final String AUTO = "auto";

    private String primary = AUTO;
    private boolean failover = true;
    private List<DbKind> order = new ArrayList<>(DbKind.DEFAULT_ORDER);
    private int healthIntervalSec = 15;
    private int probeTimeoutMs = 3000;
    private final Map<DbKind, Conn> conns = new EnumMap<>(DbKind.class);

    public DbSettings() {
        for (DbKind k : DbKind.values()) conns.put(k, new Conn());
    }

    /** 접속 정보 하나 */
    public static class Conn {
        private boolean enabled;
        private String url;
        private String username;
        private String password;
        /** JDBC 드라이버 클래스 (비우면 DB 기본 - 예: Sybase 를 SAP jConnect 로 쓰면 com.sybase.jdbc4.jdbc.SybDriver) */
        private String driver;
        /** MariaDB : 웹이 직접 띄우는 임베디드 / H2 : 실행 폴더의 파일 DB (url 이 비었을 때) */
        private boolean embedded;
        private int maximumPoolSize = 10;
        private long connectionTimeout = 5000;
        /** 비밀번호가 설정 파일에 있는지 (화면 표시용 - 값은 보여주지 않음) */
        private boolean passwordInFile;

        public Conn copy() {
            Conn c = new Conn();
            c.enabled = enabled;
            c.url = url;
            c.username = username;
            c.password = password;
            c.driver = driver;
            c.embedded = embedded;
            c.maximumPoolSize = maximumPoolSize;
            c.connectionTimeout = connectionTimeout;
            c.passwordInFile = passwordInFile;
            return c;
        }

        public boolean hasUrl() { return url != null && !url.trim().isEmpty(); }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriver() { return driver; }
        public void setDriver(String driver) { this.driver = driver; }
        public boolean isEmbedded() { return embedded; }
        public void setEmbedded(boolean embedded) { this.embedded = embedded; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public boolean isPasswordInFile() { return passwordInFile; }
        public void setPasswordInFile(boolean passwordInFile) { this.passwordInFile = passwordInFile; }
    }

    /** primary 가 DB 이름이면 그 종류, auto 면 null */
    public DbKind fixedPrimary() {
        return DbKind.of(primary);
    }

    public Conn conn(DbKind k) { return conns.get(k); }

    public String getPrimary() { return primary; }
    public void setPrimary(String primary) {
        DbKind k = DbKind.of(primary);
        this.primary = k == null ? AUTO : k.key();
    }
    public boolean isFailover() { return failover; }
    public void setFailover(boolean failover) { this.failover = failover; }
    public List<DbKind> getOrder() { return order; }
    /** 빠진 DB 는 기본 순서대로 뒤에 붙임 */
    public void setOrder(List<DbKind> order) {
        List<DbKind> o = new ArrayList<>();
        for (DbKind k : order) if (k != null && !o.contains(k)) o.add(k);
        for (DbKind k : DbKind.DEFAULT_ORDER) if (!o.contains(k)) o.add(k);
        this.order = o;
    }
    public int getHealthIntervalSec() { return healthIntervalSec; }
    public void setHealthIntervalSec(int healthIntervalSec) { this.healthIntervalSec = Math.max(5, healthIntervalSec); }
    public int getProbeTimeoutMs() { return probeTimeoutMs; }
    public void setProbeTimeoutMs(int probeTimeoutMs) { this.probeTimeoutMs = Math.max(500, probeTimeoutMs); }
}
