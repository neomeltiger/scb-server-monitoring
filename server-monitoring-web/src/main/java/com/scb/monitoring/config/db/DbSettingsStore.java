package com.scb.monitoring.config.db;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 설정 파일 conf/scb-db.properties 읽기·쓰기 (배포 패키지에서 주 DB 선택 / 관리자 화면 [DB 연결 관리] 저장).
 * <ul>
 *   <li>파일 위치 : 환경변수 SCB_DB_CONFIG (app.db.config-file), 비우면 실행 폴더의 conf/scb-db.properties</li>
 *   <li>파일에 없는 값은 기존 설정(app.db.* / 환경변수 SCB_DB, MYSQL_*, SCB_ORACLE_*, SYBASE_*)을 그대로 씀</li>
 *   <li>비밀번호 : 관리자 화면에서 저장하면 ENC(...) (AES-256-GCM) - 키는 같은 폴더의 scb-db.key (처음 저장할 때 만듦).
 *       파일에 직접 적은 평문도 읽지만 권장하지 않음</li>
 *   <li>저장할 때 주석·다른 줄은 그대로 두고 해당 키 줄만 바꿈</li>
 * </ul>
 */
public class DbSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(DbSettingsStore.class);
    private static final String ENC_PREFIX = "ENC(";
    private static final String TEMPLATE = "db/scb-db.properties.template";

    private final DbProperties defaults;
    private final File file;
    private final File keyFile;
    private volatile DbSettings current;

    public DbSettingsStore(DbProperties defaults, String path) {
        this.defaults = defaults;
        this.file = path == null || path.trim().isEmpty()
                ? new File(new File(System.getProperty("user.dir", "."), "conf"), "scb-db.properties")
                : new File(path.trim());
        this.keyFile = new File(file.getAbsoluteFile().getParentFile(), "scb-db.key");
        reload();
    }

    public File getFile() { return file; }

    public DbSettings get() { return current; }

    // ---------------------------------------------------------------- 읽기

    public synchronized DbSettings reload() {
        DbSettings s = fromDefaults();
        Map<String, String> f = readFile();
        if (!f.isEmpty()) log.info("DB 설정 파일 : {}", file.getAbsolutePath());
        String v;
        if (!(v = f.getOrDefault("db.primary", "")).isEmpty()) s.setPrimary(v);
        if (!(v = f.getOrDefault("db.failover", "")).isEmpty()) s.setFailover(Boolean.parseBoolean(v));
        if (!(v = f.getOrDefault("db.order", "")).isEmpty()) {
            List<DbKind> order = new ArrayList<>();
            for (String k : v.split(",")) order.add(DbKind.of(k));
            s.setOrder(order);
        }
        if (!(v = f.getOrDefault("db.health-interval-sec", "")).isEmpty()) s.setHealthIntervalSec(parseInt(v, 15));
        if (!(v = f.getOrDefault("db.probe-timeout-ms", "")).isEmpty()) s.setProbeTimeoutMs(parseInt(v, 3000));
        for (DbKind k : DbKind.values()) {
            DbSettings.Conn c = s.conn(k);
            String p = "db." + k.key() + ".";
            if (!(v = f.getOrDefault(p + "enabled", "")).isEmpty()) c.setEnabled(Boolean.parseBoolean(v));
            if (!(v = f.getOrDefault(p + "embedded", "")).isEmpty()) c.setEmbedded(Boolean.parseBoolean(v));
            if (!(v = f.getOrDefault(p + "url", "")).isEmpty()) c.setUrl(v);
            if (!(v = f.getOrDefault(p + "username", "")).isEmpty()) c.setUsername(v);
            if (!(v = f.getOrDefault(p + "driver", "")).isEmpty()) c.setDriver(v);
            if (!(v = f.getOrDefault(p + "password", "")).isEmpty()) {
                c.setPassword(decrypt(v, k));
                c.setPasswordInFile(true);
            }
            if (!(v = f.getOrDefault(p + "maximum-pool-size", "")).isEmpty()) c.setMaximumPoolSize(parseInt(v, 10));
            if (!(v = f.getOrDefault(p + "connection-timeout", "")).isEmpty()) c.setConnectionTimeout(parseInt(v, 5000));
        }
        current = s;
        return s;
    }

    /** 파일이 없을 때의 값 = 지금까지의 설정 (application.yml app.db.* + 환경변수) */
    private DbSettings fromDefaults() {
        DbSettings s = new DbSettings();
        s.setPrimary(defaults.getPrimary());
        s.setProbeTimeoutMs(defaults.getProbeTimeoutMs());

        DbSettings.Conn o = s.conn(DbKind.ORACLE);
        o.setUrl(env("SCB_ORACLE_URL", DbKind.ORACLE.sampleUrl()));
        o.setUsername(env("SCB_ORACLE_USER", "scbmon"));
        o.setPassword(env("SCB_ORACLE_PASSWORD", null));
        o.setEnabled(System.getenv("SCB_ORACLE_URL") != null);
        o.setMaximumPoolSize(5);

        DbSettings.Conn y = s.conn(DbKind.SYBASE);
        y.setUrl("jdbc:jtds:sybase://" + env("SYBASE_HOST", "127.0.0.1") + ":" + env("SYBASE_PORT", "5000") + "/"
                + env("SYBASE_DB", "scbmon") + ";charset=" + env("SYBASE_CHARSET", "utf8") + ";useLOBs=false;appName=server-monitoring-web");
        y.setUsername(env("SYBASE_USER", "scbmon"));
        y.setPassword(env("SYBASE_PASSWORD", null));
        y.setEnabled(System.getenv("SYBASE_HOST") != null);
        y.setMaximumPoolSize(5);

        DbSettings.Conn m = s.conn(DbKind.MYSQL);
        copy(defaults.getMysql(), m);
        m.setEnabled(true);

        DbSettings.Conn a = s.conn(DbKind.MARIADB);
        copy(defaults.getMariadb(), a);
        a.setEmbedded(defaults.getEmbedded().isEnabled());
        a.setEnabled(a.isEmbedded() || a.hasUrl());

        DbSettings.Conn h = s.conn(DbKind.H2);
        h.setEmbedded(true);
        h.setEnabled(defaults.getH2().isEnabled());
        h.setMaximumPoolSize(defaults.getH2().getMaximumPoolSize());
        return s;
    }

    private static void copy(DbProperties.Conn from, DbSettings.Conn to) {
        to.setUrl(from.getUrl());
        to.setUsername(from.getUsername());
        to.setPassword(from.getPassword());
        to.setMaximumPoolSize(from.getMaximumPoolSize());
        to.setConnectionTimeout(from.getConnectionTimeout());
    }

    private Map<String, String> readFile() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!file.isFile()) return m;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.indexOf('=') < 0) continue;
                m.put(t.substring(0, t.indexOf('=')).trim(), t.substring(t.indexOf('=') + 1).trim());
            }
        } catch (IOException e) {
            log.warn("DB 설정 파일 읽기 실패 {} : {}", file.getAbsolutePath(), e.getMessage());
        }
        return m;
    }

    // ---------------------------------------------------------------- 저장 (관리자 화면)

    /** 주 DB 선택 · 자동 교체 · 우선순위 */
    public synchronized DbSettings saveGeneral(String primary, Boolean failover, List<DbKind> order) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (primary != null) {
            DbKind k = DbKind.of(primary);
            kv.put("db.primary", k == null ? DbSettings.AUTO : k.key());
        }
        if (failover != null) kv.put("db.failover", String.valueOf(failover));
        if (order != null && !order.isEmpty()) {
            DbSettings tmp = new DbSettings();
            tmp.setOrder(order);
            List<String> keys = new ArrayList<>();
            for (DbKind k : tmp.getOrder()) keys.add(k.key());
            kv.put("db.order", String.join(",", keys));
        }
        write(kv);
        return reload();
    }

    /**
     * 접속 정보 하나. password : null 이면 그대로, 값이 있으면 암호화해서 저장, clearPassword 면 파일에서 지움.
     */
    public synchronized DbSettings saveConn(DbKind k, boolean enabled, Boolean embedded, String url, String username,
                                            String password, boolean clearPassword) {
        String p = "db." + k.key() + ".";
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(p + "enabled", String.valueOf(enabled));
        if (embedded != null) kv.put(p + "embedded", String.valueOf(embedded));
        if (url != null) kv.put(p + "url", clean(url));
        if (username != null) kv.put(p + "username", clean(username));
        if (clearPassword) kv.put(p + "password", "");
        else if (password != null && !password.isEmpty()) kv.put(p + "password", encrypt(password));
        write(kv);
        return reload();
    }

    private static String clean(String v) {
        return v.replace("\r", "").replace("\n", "").trim();
    }

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=.*$");

    private void write(Map<String, String> kv) {
        try {
            List<String> lines = file.isFile() ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) : template();
            Map<String, String> left = new LinkedHashMap<>(kv);
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                java.util.regex.Matcher m = KEY_LINE.matcher(line);
                if (m.matches() && !line.trim().startsWith("#") && left.containsKey(m.group(1))) {
                    out.add(m.group(1) + "=" + left.remove(m.group(1)));
                } else {
                    out.add(line);
                }
            }
            if (!left.isEmpty()) {
                out.add("");
                out.add("# 관리자 화면 [DB 연결 관리] 에서 저장");
                for (Map.Entry<String, String> e : left.entrySet()) out.add(e.getKey() + "=" + e.getValue());
            }
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("폴더를 만들 수 없음 : " + dir);
            File tmp = new File(dir, file.getName() + ".tmp");
            Files.write(tmp.toPath(), (String.join(System.lineSeparator(), out) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("DB 설정 저장 : {} ({})", file.getAbsolutePath(), String.join(", ", kv.keySet()));
        } catch (IOException e) {
            throw new IllegalStateException("DB 설정 파일 저장 실패 : " + file.getAbsolutePath() + " - " + e.getMessage(), e);
        }
    }

    private static List<String> template() throws IOException {
        try (InputStream in = DbSettingsStore.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) return new ArrayList<>();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            List<String> out = new ArrayList<>();
            for (String l : new String(bo.toByteArray(), StandardCharsets.UTF_8).split("\r?\n", -1)) out.add(l);
            while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
            return out;
        }
    }

    // ---------------------------------------------------------------- 비밀번호 암호화

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(all) + ")";
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("비밀번호 암호화 실패 : " + e.getMessage(), e);
        }
    }

    private String decrypt(String v, DbKind k) {
        if (!v.startsWith(ENC_PREFIX) || !v.endsWith(")")) return v;           // 평문 (직접 적은 값)
        try {
            byte[] all = Base64.getDecoder().decode(v.substring(ENC_PREFIX.length(), v.length() - 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            log.warn("DB 설정 : {} 비밀번호를 풀 수 없습니다 (scb-db.key 가 바뀌었거나 없음) - 관리자 화면에서 다시 입력하세요", k.label());
            return null;
        }
    }

    private byte[] key(boolean create) throws IOException {
        if (keyFile.isFile()) return Base64.getDecoder().decode(new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.US_ASCII).trim());
        if (!create) throw new IOException("키 파일 없음 : " + keyFile);
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        File dir = keyFile.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("폴더를 만들 수 없음 : " + dir);
        Files.write(keyFile.toPath(), Base64.getEncoder().encodeToString(k).getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignore) {
            // Windows
        }
        log.info("DB 설정 : 비밀번호 암호화 키 생성 {}", keyFile.getAbsolutePath());
        return k;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
