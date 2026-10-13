package com.scb.monitoring.config.db;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 임베디드 MariaDB : 웹이 MariaDB 서버 프로그램(mariadbd)을 직접 초기화·시작·종료합니다.
 * <ul>
 *   <li>프로그램 : {home}/bin/mariadbd(.exe) - 실행 패키지 mariadb/windows-x64 (MariaDB 10.11 LTS 공식 배포본),
 *       Solaris SPARC 는 공식 배포본이 없어 서버에서 빌드한 것을 mariadb/solaris-sparcv9 에 둠</li>
 *   <li>데이터 : {data} (처음 시작할 때 mariadb-install-db 로 만들고 scb_mon_agent 스키마·기본 데이터를 넣음)</li>
 *   <li>접속 : 127.0.0.1:{port} (기본 13307) 만 엶. root 비밀번호는 처음 만들 때 무작위로 정해
 *       데이터 폴더 옆 {data}.scb-embedded.properties 에 저장 (환경변수 SCB_MARIADB_PASSWORD 를 주면 그 값)</li>
 *   <li>이미 그 포트에서 돌고 있으면 (웹을 강제 종료해 남은 경우 등) 다시 띄우지 않고 그대로 사용</li>
 * </ul>
 */
public class EmbeddedMariaDb {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMariaDb.class);
    public static final String SCHEMA = "scb_mon_agent";
    static final String CRED_FILE = "scb-embedded.properties";

    private final DbProperties.Embedded cfg;
    private final File home;
    private final File data;
    private final String configuredPassword;
    private String password;
    private Process process;
    private boolean startedByUs;

    public EmbeddedMariaDb(DbProperties.Embedded cfg, String configuredPassword) {
        this.cfg = cfg;
        this.home = resolveHome(cfg.getHome());
        this.data = resolveData(cfg.getData());
        this.configuredPassword = configuredPassword;
    }

    // ---------------------------------------------------------------- 위치

    /** 이 OS 의 패키지 폴더 이름 : windows-x64 / solaris-sparcv9 / solaris-x64 / linux-x64 */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows-x64";
        if (os.contains("sunos") || os.contains("solaris")) return arch.contains("sparc") ? "solaris-sparcv9" : "solaris-x64";
        return "linux-x64";
    }

    static File resolveHome(String configured) {
        if (configured != null && !configured.trim().isEmpty()) return new File(configured.trim());
        File cwd = new File(System.getProperty("user.dir", "."));
        for (File base : new File[] {cwd, cwd.getParentFile()}) {
            if (base == null) continue;
            File f = new File(new File(base, "mariadb"), platform());
            if (f.isDirectory()) return f;
        }
        return new File(new File(cwd, "mariadb"), platform());
    }

    static File resolveData(String configured) {
        if (configured != null && !configured.trim().isEmpty()) return new File(configured.trim());
        return new File(new File(System.getProperty("user.dir", "."), "data"), "mariadb");
    }

    public File getHome() { return home; }
    public File getData() { return data; }
    public int getPort() { return cfg.getPort(); }
    /** root 비밀번호 : SCB_MARIADB_PASSWORD, 없으면 {data}.scb-embedded.properties (처음 쓸 때 만듦 - 쓰지 않으면 파일도 만들지 않음) */
    public synchronized String getPassword() {
        if (password == null) {
            password = configuredPassword != null && !configuredPassword.isEmpty() ? configuredPassword : storedOrNewPassword(data);
        }
        return password;
    }
    public boolean isEnabled() { return cfg.isEnabled(); }
    public boolean isStartedByUs() { return startedByUs; }

    /** 서버 프로그램 (mariadbd, 이전 이름 mysqld) */
    File serverBinary() {
        boolean win = platform().startsWith("windows");
        for (String n : new String[] {"mariadbd", "mysqld"}) {
            File f = new File(new File(home, "bin"), win ? n + ".exe" : n);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 프로그램이 있는지 (Solaris SPARC 는 빌드해서 넣어야 함) */
    public boolean isAvailable() {
        return serverBinary() != null;
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://127.0.0.1:" + cfg.getPort() + "/" + SCHEMA;
    }

    // ---------------------------------------------------------------- 시작 / 종료

    /** 실행 중이 아니면 시작 (처음이면 데이터 폴더·스키마 생성). 이미 그 포트에서 돌고 있으면 그대로 사용 */
    public synchronized void ensureStarted() {
        if (!cfg.isEnabled()) throw new IllegalStateException("임베디드 MariaDB 가 꺼져 있습니다 (app.db.embedded.enabled=false)");
        if (process != null && process.isAlive()) return;
        if (portOpen()) {
            log.info("MARIADB 임베디드 : 127.0.0.1:{} 에서 이미 실행 중 - 그대로 사용", cfg.getPort());
            ensureSchema();
            return;
        }
        File bin = serverBinary();
        if (bin == null) {
            throw new IllegalStateException("임베디드 MariaDB 프로그램이 없습니다 : " + new File(home, "bin").getAbsolutePath()
                    + (platform().startsWith("solaris") ? " (Solaris 는 MariaDB 공식 배포본이 없어 mariadb/" + platform() + "/README.txt 대로 빌드해 넣으세요)" : ""));
        }
        try {
            boolean fresh = !new File(data, "mysql").isDirectory();
            if (fresh) initDataDir();
            start(bin);
            if (fresh && !platform().startsWith("windows")) setRootPassword();
            ensureSchema();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("임베디드 MariaDB 시작 실패 : " + e.getMessage(), e);
        }
    }

    private void initDataDir() throws IOException, InterruptedException {
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("데이터 폴더를 만들 수 없습니다 : " + data);
        List<String> cmd = new ArrayList<>();
        if (platform().startsWith("windows")) {
            cmd.add(new File(new File(home, "bin"), "mariadb-install-db.exe").getAbsolutePath());
            cmd.add("--datadir=" + data.getAbsolutePath());
            cmd.add("--password=" + getPassword());
        } else {
            File script = new File(new File(home, "scripts"), "mariadb-install-db");
            if (!script.isFile()) script = new File(new File(home, "scripts"), "mysql_install_db");
            cmd.add("sh");
            cmd.add(script.getAbsolutePath());
            cmd.add("--no-defaults");
            cmd.add("--basedir=" + home.getAbsolutePath());
            cmd.add("--datadir=" + data.getAbsolutePath());
            cmd.add("--auth-root-authentication-method=normal");     // root 는 비밀번호 방식 (뒤에서 설정)
            if ("root".equals(System.getProperty("user.name"))) cmd.add("--user=root");
        }
        log.info("MARIADB 임베디드 : 데이터 폴더 처음 생성 {}", data.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).directory(home).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile("mariadb-install.log"))).start();
        if (!p.waitFor(cfg.getStartTimeoutSec() * 2L, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("mariadb-install-db 실패 - " + logFile("mariadb-install.log"));
        }
    }

    private void start(File bin) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("--no-defaults");
        cmd.add("--basedir=" + home.getAbsolutePath());
        cmd.add("--datadir=" + data.getAbsolutePath());
        cmd.add("--port=" + cfg.getPort());
        cmd.add("--bind-address=127.0.0.1");
        cmd.add("--character-set-server=utf8mb4");
        cmd.add("--collation-server=utf8mb4_general_ci");
        cmd.add("--log-error=" + logFile("mariadb.err").getAbsolutePath());
        cmd.add("--log-warnings=1");           // 연결 확인(포트 검사) 때마다 남는 Aborted connection 경고 끔
        if (!platform().startsWith("windows")) {
            cmd.add("--socket=" + new File(data, "mariadb.sock").getAbsolutePath());
            cmd.add("--pid-file=" + new File(data, "mariadb.pid").getAbsolutePath());
            if ("root".equals(System.getProperty("user.name"))) cmd.add("--user=root");
        }
        log.info("MARIADB 임베디드 시작 : {} port={} data={}", bin.getAbsolutePath(), cfg.getPort(), data.getAbsolutePath());
        process = new ProcessBuilder(cmd).directory(home).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile("mariadb.out"))).start();
        startedByUs = true;
        long deadline = System.currentTimeMillis() + cfg.getStartTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) throw new IOException("mariadbd 가 바로 종료됨 (exit " + process.exitValue() + ") - " + logFile("mariadb.err"));
            if (portOpen()) {
                log.info("MARIADB 임베디드 준비 완료 (127.0.0.1:{})", cfg.getPort());
                return;
            }
            Thread.sleep(300);
        }
        throw new IOException("mariadbd 가 " + cfg.getStartTimeoutSec() + "초 안에 준비되지 않음 - " + logFile("mariadb.err"));
    }

    /** Solaris / Linux : install-db 가 root 를 비밀번호 없이 만들므로 저장된 비밀번호로 설정 */
    private void setRootPassword() {
        String pw = getPassword().replace("'", "''");
        try (Connection c = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:" + cfg.getPort() + "/", "root", "");
             Statement st = c.createStatement()) {
            st.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + pw + "'");
            st.execute("FLUSH PRIVILEGES");
        } catch (SQLException e) {
            log.warn("MARIADB 임베디드 root 비밀번호 설정 실패 : {}", e.getMessage());
        }
    }

    /** scb_mon_agent 테이블이 없으면 schema-mysql.sql + data.sql + users.sql 실행 (MariaDB 는 MySQL 스키마와 호환) */
    public void ensureSchema() {
        try (Connection c = rootConnection()) {
            int tables;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA + "'")) {
                rs.next();
                tables = rs.getInt(1);
            }
            if (tables > 0) return;
            log.info("MARIADB 임베디드 : {} 스키마 생성 (db/schema-mysql.sql, data.sql, users.sql)", SCHEMA);
            for (String script : new String[] {"db/schema-mysql.sql", "db/data.sql", "db/users.sql"}) {
                ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(script), StandardCharsets.UTF_8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("임베디드 MariaDB 스키마 확인 실패 : " + e.getMessage(), e);
        }
    }

    Connection rootConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://127.0.0.1:" + cfg.getPort() + "/", "root", getPassword());
    }

    /** 우리가 띄운 경우만 종료 (SHUTDOWN 문 → 최대 30초 대기 → 강제 종료) */
    public synchronized void stop() {
        if (process == null || !process.isAlive()) return;
        log.info("MARIADB 임베디드 종료");
        try (Connection c = rootConnection(); Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (SQLException e) {
            log.debug("SHUTDOWN : {}", e.getMessage());
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                log.warn("MARIADB 임베디드가 30초 안에 끝나지 않아 강제 종료");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", cfg.getPort()), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private File logFile(String name) {
        String lp = System.getProperty("LOG_PATH");
        File dir = lp != null && !lp.isEmpty() ? new File(lp) : data.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        return new File(dir, name);
    }

    // ---------------------------------------------------------------- 비밀번호 파일

    /** 비밀번호 파일 : 데이터 폴더 옆 {data}.scb-embedded.properties (install-db 는 빈 데이터 폴더만 받음) */
    static File credFile(File data) {
        File abs = data.getAbsoluteFile();
        return new File(abs.getParentFile(), abs.getName() + "." + CRED_FILE);
    }

    /** credFile 의 root.password, 없으면 새로 만들어 저장 (소유자만 읽기) */
    static String storedOrNewPassword(File data) {
        File f = credFile(data);
        Properties p = new Properties();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
                String pw = p.getProperty("root.password");
                if (pw != null && !pw.isEmpty()) return pw;
            } catch (IOException e) {
                log.warn("MARIADB 임베디드 비밀번호 파일을 읽지 못함 : {}", e.getMessage());
            }
        }
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        String pw = sb.toString();
        if (f.getParentFile() != null && !f.getParentFile().isDirectory()) f.getParentFile().mkdirs();
        p.setProperty("root.password", pw);
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, "scb_monitor_web embedded MariaDB root password (127.0.0.1 only) - do not share");
        } catch (IOException e) {
            throw new IllegalStateException("임베디드 MariaDB 비밀번호 파일을 쓸 수 없습니다 : " + f, e);
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        return pw;
    }
}
