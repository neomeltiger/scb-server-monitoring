package com.scb.monitoring.config.db;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 주 DB 관리 : 시작할 때 고르기, 연결이 끊기면 다음 순위로 자동 교체, 관리자 전환, DB 별 연결 확인.
 * <ul>
 *   <li>우선순위 (기본) : Oracle → Sybase → MySQL → MariaDB → H2 - 설정 파일 db.order, 켜진(enabled) DB 만</li>
 *   <li>시작 : db.primary = auto 면 우선순위대로 연결되는 첫 DB, DB 이름이면 그 DB (안 되고 failover 면 그 다음 순위)</li>
 *   <li>자동 교체 (db.failover) : 연결을 못 얻거나 주기 확인(db.health-interval-sec)에서 끊긴 것을 알면
 *       지금 DB 보다 순위가 낮은 DB 중 연결되는 첫 DB 로. 순위가 높은 DB 로 되돌리는 것은 관리자 화면에서</li>
 *   <li>바꾼 DB 에 테이블이 없으면 스키마·기본 데이터·사용자를 만듦 ({@link DbSchema}). DB 마다 데이터는 따로임</li>
 * </ul>
 */
public class DbManager implements ApplicationEventPublisherAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DbManager.class);
    private static final int MAX_EVENTS = 50;

    /** 지금 주 DB */
    public static final class Active {
        public final DbKind kind;
        final HikariDataSource pool;
        final DbSettings.Conn conn;
        public final LocalDateTime since = LocalDateTime.now();
        public final String reason;

        Active(DbKind kind, HikariDataSource pool, DbSettings.Conn conn, String reason) {
            this.kind = kind;
            this.pool = pool;
            this.conn = conn;
            this.reason = reason;
        }
    }

    /** 시작 · 자동 교체 · 전환 · 실패 기록 (화면 표시, 최근 50건) */
    public static final class Event {
        public final LocalDateTime time = LocalDateTime.now();
        public final String type;
        public final String from;
        public final String to;
        public final String message;
        public final String user;

        Event(String type, DbKind from, DbKind to, String message, String user) {
            this.type = type;
            this.from = from == null ? null : from.key();
            this.to = to == null ? null : to.key();
            this.message = message;
            this.user = user;
        }
    }

    private final DbSettingsStore store;
    private final EmbeddedMariaDb embedded;
    private final H2Db h2;
    private final SwitchingDataSource dataSource = new SwitchingDataSource(this);
    private final Deque<Event> events = new ArrayDeque<>();
    private final ExecutorService probes = Executors.newCachedThreadPool(daemon("db-probe"));
    private final ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor(daemon("db-health"));
    private volatile Active active;
    private volatile ApplicationEventPublisher publisher;
    private volatile long lastFailoverTry;
    private volatile String lastHealth = "";

    public DbManager(DbSettingsStore store, EmbeddedMariaDb embedded, H2Db h2) {
        this.store = store;
        this.embedded = embedded;
        this.h2 = h2;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public DataSource getDataSource() { return dataSource; }

    public DbSettingsStore getStore() { return store; }

    Active active() { return active; }

    public DbKind currentKind() { return active == null ? null : active.kind; }

    public Active current() { return active; }

    public synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    public String getLastHealth() { return lastHealth; }

    // ---------------------------------------------------------------- 시작

    /** 주 DB 고르기 (애플리케이션 시작). 쓸 수 있는 DB 가 없으면 시작 실패 */
    public synchronized DbManager start() {
        DbSettings s = store.get();
        DbKind fixed = s.fixedPrimary();
        List<DbKind> candidates = new ArrayList<>();
        if (fixed != null) {
            candidates.add(fixed);
            if (s.isFailover()) candidates.addAll(lower(s, fixed));
        } else {
            candidates.addAll(s.getOrder());
        }
        List<String> why = new ArrayList<>();
        for (DbKind k : candidates) {
            DbSettings.Conn c = s.conn(k).copy();
            if (!c.isEnabled()) {
                if (k == fixed) why.add(k.label() + " 꺼짐");
                continue;
            }
            String fail = probe(k, c, s.getProbeTimeoutMs());
            if (fail != null) {
                why.add(k.label() + " " + fail);
                log.warn("DB : {} 연결 안 됨 ({}) → 다음 순위", k.label(), fail);
                continue;
            }
            try {
                String reason = (fixed == null ? "auto (우선순위)" : "db.primary=" + fixed.key())
                        + (why.isEmpty() ? "" : " - " + String.join(" / ", why)) + " → " + k.label();
                Active a = new Active(k, open(k, c), c, reason);
                DbDialect.setCurrent(k);
                active = a;
                addEvent(new Event("START", null, k, reason, null));
                log.info("DB 주 연결 : {} ({})", k.label(), reason);
                break;
            } catch (RuntimeException e) {
                why.add(k.label() + " " + NamedJdbc.rootMessage(e));
                log.warn("DB : {} 사용 불가 ({}) → 다음 순위", k.label(), NamedJdbc.rootMessage(e));
            }
        }
        if (active == null) throw new IllegalStateException("사용할 수 있는 DB 가 없습니다 : " + String.join(" / ", why));
        int sec = s.getHealthIntervalSec();
        health.scheduleWithFixedDelay(this::healthCheck, sec, sec, TimeUnit.SECONDS);
        return this;
    }

    /** 순위가 k 보다 낮은 DB (우선순위 순서) */
    private static List<DbKind> lower(DbSettings s, DbKind k) {
        List<DbKind> o = s.getOrder();
        int i = o.indexOf(k);
        return new ArrayList<>(o.subList(i + 1, o.size()));
    }

    // ---------------------------------------------------------------- 자동 교체

    /**
     * 연결 실패 → 다음 순위 DB 로. 지금 DB 가 다시 확인해 보니 살아 있으면(풀이 바빠서 시간 초과 등) 그대로 둠.
     * 이미 다른 스레드가 바꿨으면 바뀐 DB 를 돌려줌. 바꾸지 못하면 failed 그대로.
     */
    synchronized Active failover(Active failed, String why) {
        if (active != failed) return active;
        DbSettings s = store.get();
        if (System.currentTimeMillis() - lastFailoverTry < 5000) return failed;      // 모두 안 될 때 매 요청마다 시도하지 않음
        lastFailoverTry = System.currentTimeMillis();
        String now = probe(failed.kind, failed.conn, s.getProbeTimeoutMs());
        if (now == null) return failed;
        if (!s.isFailover()) {
            addEvent(new Event("FAIL", failed.kind, null, failed.kind.label() + " 연결 끊김 (" + now + ") - 자동 교체 꺼짐", null));
            log.error("DB : 주 DB {} 연결 끊김 ({}) - 자동 교체(db.failover)가 꺼져 있음", failed.kind.label(), now);
            return failed;
        }
        List<String> tried = new ArrayList<>();
        for (DbKind k : lower(s, failed.kind)) {
            DbSettings.Conn c = s.conn(k).copy();
            if (!c.isEnabled()) continue;
            String fail = probe(k, c, s.getProbeTimeoutMs());
            if (fail != null) {
                tried.add(k.label() + " " + fail);
                continue;
            }
            try {
                String reason = "자동 교체 : " + failed.kind.label() + " 연결 끊김 (" + now + ")"
                        + (tried.isEmpty() ? "" : " / " + String.join(" / ", tried)) + " → " + k.label();
                Active next = new Active(k, open(k, c), c, reason);
                switchActive(next, true, null);
                return next;
            } catch (RuntimeException e) {
                tried.add(k.label() + " " + NamedJdbc.rootMessage(e));
            }
        }
        addEvent(new Event("FAIL", failed.kind, null, failed.kind.label() + " 연결 끊김 (" + now + ") - 바꿀 DB 없음"
                + (tried.isEmpty() ? "" : " : " + String.join(" / ", tried)), null));
        log.error("DB : 주 DB {} 연결 끊김 ({}) - 바꿀 수 있는 다음 순위 DB 가 없음 {}", failed.kind.label(), now, tried);
        return failed;
    }

    /** 쓰는 중 연결 끊김 오류 → 뒤에서 교체 확인 (요청 스레드는 기다리지 않음) */
    void failoverAsync(Active a, String why) {
        if (active != a) return;
        probes.submit(() -> {
            try {
                failover(a, why);
            } catch (RuntimeException e) {
                log.warn("DB 교체 확인 오류 : {}", e.getMessage());
            }
        });
    }

    private void healthCheck() {
        Active a = active;
        if (a == null) return;
        try {
            String fail = probe(a.kind, a.conn, store.get().getProbeTimeoutMs());
            if (fail == null) {
                lastHealth = LocalDateTime.now().withNano(0) + " 정상";
                return;
            }
            lastHealth = LocalDateTime.now().withNano(0) + " " + fail;
            log.warn("DB : 주 DB {} 연결 확인 실패 ({})", a.kind.label(), fail);
            failover(a, fail);
        } catch (RuntimeException e) {
            log.warn("DB 연결 확인 오류 : {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 관리자 전환

    /** 관리자 전환 결과 */
    public static final class SwitchResult {
        public final DbKind from;
        public final DbKind to;
        public final List<DbCopy.Result> copied;

        SwitchResult(DbKind from, DbKind to, List<DbCopy.Result> copied) {
            this.from = from;
            this.to = to;
            this.copied = copied;
        }
    }

    /** 관리자 : 주 DB 를 k 로 (copyData 면 지금 DB 의 데이터를 먼저 복사) */
    public synchronized SwitchResult switchTo(DbKind k, boolean copyData, String user) {
        Active cur = active;
        if (cur.kind == k) throw new IllegalArgumentException("이미 " + k.label() + " 를 쓰고 있습니다");
        DbSettings s = store.get();
        DbSettings.Conn c = s.conn(k).copy();
        if (!c.isEnabled()) throw new IllegalArgumentException(k.label() + " 는 꺼져 있습니다 (연결 설정에서 사용 체크)");
        String fail = probe(k, c, s.getProbeTimeoutMs());
        if (fail != null) throw new IllegalArgumentException(k.label() + " 연결 안 됨 : " + fail);
        HikariDataSource pool = open(k, c);
        List<DbCopy.Result> copied = null;
        if (copyData) {
            try {
                copied = DbCopy.copy(cur.pool, pool);
                if (k == DbKind.ORACLE) {
                    try (Connection con = pool.getConnection()) {
                        DbSchema.restartOracleIdentities(con, DbDialect.identityColumns(DbSchema.resource(DbSchema.ANSI)));
                    }
                }
            } catch (SQLException | RuntimeException e) {
                pool.close();
                throw new IllegalStateException("데이터 복사 실패 (전환하지 않음) : " + NamedJdbc.rootMessage(e), e);
            }
        }
        String reason = "관리자 전환 (" + user + ") : " + cur.kind.label() + " → " + k.label() + (copyData ? " (데이터 복사)" : "");
        switchActive(new Active(k, pool, c, reason), false, user);
        return new SwitchResult(cur.kind, k, copied);
    }

    private void switchActive(Active next, boolean automatic, String user) {
        Active old = active;
        DbDialect.setCurrent(next.kind);
        active = next;
        addEvent(new Event(automatic ? "FAILOVER" : "SWITCH", old == null ? null : old.kind, next.kind, next.reason, user));
        if (automatic) log.error("DB 주 연결 교체 : {}", next.reason);
        else log.warn("DB 주 연결 전환 : {}", next.reason);
        if (old != null && old.pool != next.pool) {
            health.schedule(() -> old.pool.close(), 30, TimeUnit.SECONDS);       // 쓰던 연결이 끝나도록 잠시 뒤 닫음
        }
        ApplicationEventPublisher p = publisher;
        if (p != null) {
            DbSwitchedEvent ev = new DbSwitchedEvent(this, old == null ? null : old.kind, next.kind, automatic);
            probes.submit(() -> {
                try {
                    p.publishEvent(ev);
                } catch (RuntimeException e) {
                    log.warn("DB 전환 후 처리 실패 : {}", e.getMessage());
                }
            });
        }
    }

    private synchronized void addEvent(Event e) {
        events.addFirst(e);
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    // ---------------------------------------------------------------- 연결 확인

    /** 화면 표시용 상태 하나 */
    public static final class Status {
        public String key;
        public String label;
        public int rank;
        public boolean enabled;
        public boolean embedded;
        public String url;
        public String username;
        public boolean hasPassword;
        public boolean passwordInFile;
        public boolean current;
        public boolean ok;
        public String message;
        public long ms;
        public String sampleUrl;
    }

    /** 모든 DB 연결 확인 (동시에, 우선순위 순서) */
    public List<Status> statuses() {
        DbSettings s = store.get();
        Active a = active;
        List<Future<Status>> fs = new ArrayList<>();
        int rank = 0;
        for (DbKind k : s.getOrder()) {
            final int r = ++rank;
            DbSettings.Conn c = s.conn(k).copy();
            fs.add(probes.submit(() -> status(k, c, r, a, s.getProbeTimeoutMs())));
        }
        List<Status> out = new ArrayList<>();
        for (Future<Status> f : fs) {
            try {
                out.add(f.get(s.getProbeTimeoutMs() + 5000L, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        return out;
    }

    private Status status(DbKind k, DbSettings.Conn c, int rank, Active a, int timeoutMs) {
        Status st = new Status();
        st.key = k.key();
        st.label = k.label();
        st.rank = rank;
        st.enabled = c.isEnabled();
        st.embedded = embeddedConn(k, c);
        st.url = st.embedded ? embeddedUrl(k) : c.getUrl();
        st.username = st.embedded ? null : c.getUsername();
        st.hasPassword = c.getPassword() != null && !c.getPassword().isEmpty();
        st.passwordInFile = c.isPasswordInFile();
        st.current = a != null && a.kind == k;
        st.sampleUrl = k.sampleUrl();
        if (st.current) {
            long t0 = System.currentTimeMillis();
            st.ok = true;
            try (Connection con = a.pool.getConnection()) {
                st.ok = valid(con, k, timeoutMs);
                st.message = st.ok ? "사용 중" : "연결 확인 실패";
            } catch (SQLException e) {
                st.ok = false;
                st.message = NamedJdbc.rootMessage(e);
            }
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }
        if (!st.enabled) {
            st.message = "사용 안 함 (연결 설정에서 켜기)";
            return st;
        }
        long t0 = System.currentTimeMillis();
        String fail = probe(k, c, timeoutMs);
        st.ms = System.currentTimeMillis() - t0;
        st.ok = fail == null;
        st.message = fail == null ? (st.embedded && k == DbKind.MARIADB && !embedded.portOpen() ? "연결 가능 (전환하면 시작)" : "연결 가능") : fail;
        return st;
    }

    /** 저장하지 않은 값으로 연결 시험 (password 가 null 이면 저장된 비밀번호) */
    public Map<String, Object> test(DbKind k, DbSettings.Conn c) {
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        String fail = probe(k, c, store.get().getProbeTimeoutMs());
        m.put("ok", fail == null);
        m.put("ms", System.currentTimeMillis() - t0);
        m.put("message", fail == null ? "연결 성공" : fail);
        if (fail == null && !embeddedConn(k, c)) {
            try (Connection con = connect(k, c, store.get().getProbeTimeoutMs())) {
                boolean tables = DbSchema.tableExists(con, k, "tb_server");
                m.put("tables", tables);
                m.put("message", "연결 성공 - " + (tables ? "모니터링 테이블 있음" : "테이블 없음 (전환하면 자동 생성)"));
            } catch (SQLException e) {
                m.put("message", "연결 성공 (테이블 확인 실패 : " + NamedJdbc.rootMessage(e) + ")");
            }
        }
        return m;
    }

    /** 연결 확인 : 되면 null, 안 되면 사유. 제한 시간을 넘기면 끊음 */
    String probe(DbKind k, DbSettings.Conn c, int timeoutMs) {
        if (k == DbKind.H2 && !c.hasUrl()) {
            return h2 != null && h2.isEnabled() ? null : "H2 꺼짐";
        }
        if (k == DbKind.MARIADB && c.isEmbedded()) {
            if (embedded == null || !embedded.isEnabled()) return "임베디드 MariaDB 꺼짐";
            if (embedded.portOpen()) return null;
            return embedded.isAvailable() ? null : "MariaDB 프로그램 없음 (mariadb/" + EmbeddedMariaDb.platform() + ")";
        }
        if (!c.hasUrl()) return "URL 없음";
        Future<String> f = probes.submit(() -> {
            try (Connection con = connect(k, c, timeoutMs)) {
                return valid(con, k, timeoutMs) ? null : "연결 확인 실패";
            } catch (Exception e) {
                return NamedJdbc.rootMessage(e);
            }
        });
        try {
            return f.get(timeoutMs + 2000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return "응답 없음 (" + (timeoutMs + 2000) / 1000 + "초)";
        } catch (Exception e) {
            return NamedJdbc.rootMessage(e);
        }
    }

    /** 풀 없이 직접 연결 (제한 시간 적용) */
    static Connection connect(DbKind k, DbSettings.Conn c, int timeoutMs) throws SQLException {
        String driver = driver(k, c);
        Driver d;
        try {
            d = (Driver) Class.forName(driver).newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new SQLException(k.label() + " JDBC 드라이버 없음 (" + driver + ")", "08001");
        }
        Properties p = new Properties();
        if (c.getUsername() != null) p.setProperty("user", c.getUsername());
        if (c.getPassword() != null) p.setProperty("password", c.getPassword());
        int sec = Math.max(1, timeoutMs / 1000);
        switch (driver.equals(k.driver()) ? k : DbKind.H2) {            // 다른 드라이버면 시간 제한 속성을 넣지 않음
            case ORACLE:
                p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(timeoutMs));
                p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(timeoutMs * 2));
                break;
            case SYBASE:
                p.setProperty("loginTimeout", String.valueOf(sec));
                p.setProperty("socketTimeout", String.valueOf(sec * 2));
                break;
            case MYSQL:
            case MARIADB:
                p.setProperty("connectTimeout", String.valueOf(timeoutMs));
                p.setProperty("socketTimeout", String.valueOf(timeoutMs * 2));
                break;
            default:
                break;
        }
        Connection con = d.connect(c.getUrl(), p);
        if (con == null) throw new SQLException(k.label() + " 드라이버가 받지 않는 URL 입니다 : " + c.getUrl(), "08001");
        return con;
    }

    static String driver(DbKind k, DbSettings.Conn c) {
        return c.getDriver() == null || c.getDriver().trim().isEmpty() ? k.driver() : c.getDriver().trim();
    }

    private static boolean valid(Connection con, DbKind k, int timeoutMs) throws SQLException {
        if (k == DbKind.SYBASE) {                                              // jTDS 는 isValid() 미지원
            try (Statement st = con.createStatement()) {
                st.setQueryTimeout(Math.max(1, timeoutMs / 1000));
                st.execute(k.testQuery());
                return true;
            }
        }
        return con.isValid(Math.max(1, timeoutMs / 1000));
    }

    private boolean embeddedConn(DbKind k, DbSettings.Conn c) {
        return k == DbKind.H2 && !c.hasUrl() || k == DbKind.MARIADB && c.isEmbedded();
    }

    private String embeddedUrl(DbKind k) {
        if (k == DbKind.H2) return h2 == null ? null : h2.jdbcUrl();
        return embedded == null ? null : embedded.jdbcUrl();
    }

    // ---------------------------------------------------------------- 풀

    /** 주 DB 풀 열기 + 스키마 준비 */
    HikariDataSource open(DbKind k, DbSettings.Conn c) {
        HikariDataSource ds;
        if (k == DbKind.H2 && !c.hasUrl()) {
            ds = DbSelection.h2Pool(h2);
        } else if (k == DbKind.MARIADB && c.isEmbedded()) {
            embedded.ensureStarted();
            ds = DbSelection.pool("mariadb-pool", k.driver(), c.getMaximumPoolSize(), c.getConnectionTimeout(),
                    embedded.jdbcUrl(), "root", embedded.getPassword());
        } else {
            ds = DbSelection.pool(k.key() + "-pool", driver(k, c), c.getMaximumPoolSize(), c.getConnectionTimeout(),
                    c.getUrl(), c.getUsername(), c.getPassword());
            if (k == DbKind.SYBASE) ds.setConnectionTestQuery(k.testQuery());
        }
        try {
            DbSchema.ensure(ds, k);
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
        return ds;
    }

    @Override
    public void destroy() {
        health.shutdownNow();
        probes.shutdownNow();
        Active a = active;
        if (a != null) a.pool.close();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
