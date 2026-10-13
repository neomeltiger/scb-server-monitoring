package com.scb.monitoring.dbsetup;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.scb.monitoring.config.db.DbDialect;
import com.scb.monitoring.config.db.DbKind;
import com.scb.monitoring.config.db.DbSchema;
import com.scb.monitoring.config.db.NamedJdbc;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 신규 DB 초기 구성 (관리자 화면에서 단계마다 버튼으로만 실행 - 자동 실행 없음).
 * <pre>
 *  1) 연결 확인   입력한 계정으로 접속 → DB 제품 · 버전, 스키마가 있는지, 이미 있는 테이블 · 기초 데이터
 *  2) 스키마 생성  없을 때만 만듦 (있으면 건너뛰고 3) 으로) - MySQL/MariaDB CREATE DATABASE, Oracle CREATE USER,
 *                  Sybase CREATE DATABASE, H2 파일 생성. 웹이 쓸 계정(선택)도 함께
 *  3) 테이블 생성  db/setup/ddl-*.sql - 없는 테이블만 만듦 (이미 있는 테이블 · 그 인덱스는 건너뜀)
 *  4) 기초 데이터  db/setup/base-data.sql - 사용자 · 메뉴 · 서버 정보 · 설정 (수집 데이터 · 이력 제외), 기초 테이블이 비었을 때만
 * </pre>
 * 접속은 요청마다 DriverManager 로 열고 닫음 (웹이 쓰는 DB 연결과 무관). 비밀번호는 로그에 남기지 않음.
 */
@Service
public class DbSetupService {

    private static final Logger log = LoggerFactory.getLogger(DbSetupService.class);

    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-:\\[\\]]{1,100}");
    private static final Pattern SERVICE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+).*");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\w+\\s+ON\\s+(\\w+).*");
    private static final int LOGIN_TIMEOUT_SEC = 10;

    // ===================== 1) 연결 확인 =====================

    public Map<String, Object> test(DbSetupRequest r) {
        DbKind kind = kind(r);
        String schema = schema(r, kind);
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        boolean exists;
        try (Connection c = openServer(r, kind)) {
            m.put("product", c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            m.put("user", c.getMetaData().getUserName());
            exists = schemaExists(c, kind, r, schema);
        } catch (SQLException e) {
            throw TopologyException.badRequest("접속 실패 : " + NamedJdbc.rootMessage(e));
        }
        m.put("ok", true);
        m.put("ms", System.currentTimeMillis() - t0);
        m.put("kind", kind.key());
        m.put("schema", schema);
        m.put("schemaExists", exists);
        List<String> target = targetTables();
        m.put("targetTables", target);
        if (exists) {
            try (Connection c = openSchema(r, kind)) {
                Set<String> have = existingTables(c, kind, schema);
                List<String> found = new ArrayList<>();
                for (String t : target) if (have.contains(t)) found.add(t);
                m.put("existingTables", found);
                m.put("baseRows", baseRows(c, have));
            } catch (SQLException e) {
                m.put("existingTables", new ArrayList<>());
                m.put("schemaNote", "스키마는 있지만 이 계정으로 들어갈 수 없습니다 : " + NamedJdbc.rootMessage(e));
            }
        } else {
            m.put("existingTables", new ArrayList<>());
        }
        log.info("DB SETUP TEST {} schema={} exists={} by account {}", kind.key(), schema, exists, r.getUser());
        return m;
    }

    // ===================== 0) 지금 웹이 쓰는 DB 확인 (읽기만) =====================

    /**
     * 웹이 연결해 쓰고 있는 DB(주 DataSource)에 스키마 · 테이블 · 기초 데이터가 있는지.
     * 계정 입력 없이 웹의 연결을 그대로 쓰며 아무것도 바꾸지 않음.
     */
    public Map<String, Object> checkCurrent(javax.sql.DataSource ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> target = targetTables();
        try (Connection c = ds.getConnection()) {
            DbKind kind = DbSchema.kindOf(c);
            String schema;
            switch (kind) {
                case ORACLE: schema = c.getMetaData().getUserName(); break;
                case H2: schema = "PUBLIC"; break;
                default: schema = c.getCatalog();
            }
            Set<String> have = existingTables(c, kind, schema);
            List<String> present = new ArrayList<>(), missing = new ArrayList<>();
            for (String t : target) (have.contains(t) ? present : missing).add(t);
            m.put("connected", true);
            m.put("kind", kind.key());
            m.put("label", kind.label());
            m.put("product", c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            m.put("url", maskUrl(c.getMetaData().getURL()));
            m.put("user", c.getMetaData().getUserName());
            m.put("schema", schema);
            m.put("schemaExists", schema != null && !schema.isEmpty());
            m.put("targetCount", target.size());
            m.put("present", present);
            m.put("missing", missing);
            m.put("baseRows", baseRows(c, have));
            m.put("verdict", missing.isEmpty() ? "OK" : present.isEmpty() ? "EMPTY" : "PARTIAL");
        } catch (SQLException e) {
            m.put("connected", false);
            m.put("error", NamedJdbc.rootMessage(e));
        }
        return m;
    }

    /** URL 안의 password=... 는 가림 */
    static String maskUrl(String url) {
        return url == null ? null : url.replaceAll("(?i)(password=)[^;&]*", "$1*****");
    }

    // ===================== 2) 스키마 생성 =====================

    public Map<String, Object> createSchema(DbSetupRequest r) {
        DbKind kind = kind(r);
        String schema = schema(r, kind);
        List<String> logs = new ArrayList<>();
        boolean created = false;
        try (Connection c = openServer(r, kind)) {
            c.setAutoCommit(true);
            if (schemaExists(c, kind, r, schema)) {
                logs.add("스키마 " + schema + " 가 이미 있습니다 - 만들지 않고 건너뜁니다. [3. 테이블 생성] 으로 진행하세요.");
            } else {
                String appUser = blank(r.getAppUser()) ? null : ident(r.getAppUser(), "웹 계정");
                String appPw = blank(r.getAppPassword()) ? null : secret(r.getAppPassword(), "웹 계정 비밀번호");
                try (Statement st = c.createStatement()) {
                    switch (kind) {
                        case MYSQL:
                        case MARIADB:
                            exec(st, logs, "CREATE DATABASE IF NOT EXISTS `" + schema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                            if (appUser != null) {
                                need(appPw, "웹 계정 비밀번호");
                                exec(st, logs, "CREATE USER IF NOT EXISTS '" + appUser + "'@'%' IDENTIFIED BY '" + appPw + "'", "CREATE USER '" + appUser + "'@'%' (비밀번호 생략)");
                                exec(st, logs, "GRANT ALL PRIVILEGES ON `" + schema + "`.* TO '" + appUser + "'@'%'");
                                exec(st, logs, "FLUSH PRIVILEGES");
                            }
                            break;
                        case ORACLE:
                            // Oracle 의 스키마 = 사용자 : 스키마 사용자 비밀번호가 필요 (웹 계정 비밀번호 칸)
                            need(appPw, "스키마 사용자(" + schema + ") 비밀번호 - 웹 계정 비밀번호 칸");
                            exec(st, logs, "CREATE USER " + schema + " IDENTIFIED BY \"" + appPw + "\" QUOTA UNLIMITED ON USERS",
                                    "CREATE USER " + schema + " IDENTIFIED BY ***** QUOTA UNLIMITED ON USERS");
                            exec(st, logs, "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO " + schema);
                            break;
                        case SYBASE:
                            exec(st, logs, "CREATE DATABASE " + schema);
                            if (appUser != null) {
                                need(appPw, "웹 계정 비밀번호");
                                exec(st, logs, "exec sp_addlogin " + appUser + ", '" + appPw + "', " + schema, "exec sp_addlogin " + appUser + ", *****, " + schema);
                                exec(st, logs, "USE " + schema);
                                exec(st, logs, "exec sp_changedbowner " + appUser);
                            }
                            break;
                        case H2:
                        default:
                            break;
                    }
                }
                if (kind == DbKind.H2) {
                    // H2 : 파일 DB 는 처음 접속할 때 만들어짐 (입력한 계정이 관리자)
                    try (Connection h = openH2(r, schema, false); Statement st = h.createStatement()) {
                        logs.add("H2 파일 DB 생성 : " + h2File(r, schema));
                        if (appUser != null) {
                            need(appPw, "웹 계정 비밀번호");
                            exec(st, logs, "CREATE USER IF NOT EXISTS " + appUser + " PASSWORD '" + appPw + "' ADMIN", "CREATE USER " + appUser + " PASSWORD ***** ADMIN");
                        }
                    }
                }
                created = true;
            }
        } catch (SQLException e) {
            throw TopologyException.badRequest("스키마 생성 실패 : " + NamedJdbc.rootMessage(e));
        }
        log.info("DB SETUP SCHEMA {} schema={} created={} by account {}", kind.key(), schema, created, r.getUser());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", created);
        m.put("schema", schema);
        m.put("log", logs);
        return m;
    }

    // ===================== 3) 테이블 생성 =====================

    public Map<String, Object> createTables(DbSetupRequest r) {
        DbKind kind = kind(r);
        String schema = schema(r, kind);
        String ddl = DbSchema.resource(DbSetupScripts.ddlFile(kind));
        List<String> logs = new ArrayList<>();
        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        requireSchema(r, kind, schema);
        try (Connection c = openSchema(r, kind)) {
            c.setAutoCommit(true);
            Set<String> before = existingTables(c, kind, schema);
            try (Statement st = c.createStatement()) {
                for (String sql : DbDialect.split(ddl)) {
                    Matcher t = CREATE_TABLE.matcher(sql);
                    Matcher ix = CREATE_INDEX.matcher(sql);
                    String table = t.matches() ? t.group(1).toLowerCase(Locale.ROOT) : ix.matches() ? ix.group(1).toLowerCase(Locale.ROOT) : null;
                    if (table != null && before.contains(table)) {
                        if (t.matches()) skipped.add(table);
                        continue;                       // 이미 있는 테이블과 그 인덱스는 건너뜀
                    }
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        throw new SQLException(NamedJdbc.rootMessage(e) + " - SQL : " + abbreviate(sql), e.getSQLState(), e.getErrorCode(), e);
                    }
                    if (t.matches()) {
                        created.add(table);
                        logs.add("CREATE TABLE " + table);
                    }
                }
            }
        } catch (SQLException e) {
            throw TopologyException.badRequest("테이블 생성 실패 (" + created.size() + "개 만든 뒤) : " + e.getMessage());
        }
        if (!skipped.isEmpty()) logs.add("이미 있어서 건너뜀 " + skipped.size() + "개 : " + String.join(", ", skipped));
        log.info("DB SETUP TABLES {} schema={} created={} skipped={} by account {}", kind.key(), schema, created.size(), skipped.size(), r.getUser());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", created);
        m.put("skipped", skipped);
        m.put("file", DbSetupScripts.ddlFile(kind));
        m.put("log", logs);
        return m;
    }

    // ===================== 4) 기초 데이터 =====================

    public Map<String, Object> insertBaseData(DbSetupRequest r) {
        DbKind kind = kind(r);
        String schema = schema(r, kind);
        requireSchema(r, kind, schema);
        String ansi = DbSchema.resource(DbSchema.ANSI);
        List<String> stmts = DbDialect.data(DbSchema.resource(DbSetupScripts.baseDataFile()), kind, DbDialect.identityColumns(ansi));
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = openSchema(r, kind)) {
            Set<String> have = existingTables(c, kind, schema);
            List<String> missing = new ArrayList<>();
            for (String t : DbSetupScripts.baseTables()) if (!have.contains(t)) missing.add(t);
            if (!missing.isEmpty()) throw TopologyException.badRequest("테이블이 없습니다 : " + String.join(", ", missing) + " - 먼저 [3. 테이블 생성] 을 실행하세요.");
            Map<String, Long> rows = baseRows(c, have);
            List<String> filled = new ArrayList<>();
            for (Map.Entry<String, Long> e : rows.entrySet()) if (e.getValue() > 0) filled.add(e.getKey() + " " + e.getValue() + "행");
            if (!filled.isEmpty()) {
                throw TopologyException.conflict("기초 데이터가 이미 있습니다 (" + String.join(", ", filled) + ") - 중복을 막기 위해 넣지 않았습니다.");
            }
            boolean auto = c.getAutoCommit();
            // Sybase 는 SET IDENTITY_INSERT 를 쓰므로 문장마다 커밋, 그 밖은 한 트랜잭션 (실패하면 전부 되돌림)
            c.setAutoCommit(kind == DbKind.SYBASE);
            try (Statement st = c.createStatement()) {
                for (String sql : stmts) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        throw new SQLException(NamedJdbc.rootMessage(e) + " - SQL : " + abbreviate(sql), e.getSQLState(), e.getErrorCode(), e);
                    }
                    String t = DbSetupScripts.tableOf(sql);
                    if (!t.isEmpty()) counts.merge(t, 1, Integer::sum);
                }
                if (!c.getAutoCommit()) c.commit();
            } catch (SQLException e) {
                if (!c.getAutoCommit()) c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            if (kind == DbKind.ORACLE) {
                Map<String, String> ids = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : DbDialect.identityColumns(ansi).entrySet()) {
                    if (DbSetupScripts.baseTables().contains(e.getKey())) ids.put(e.getKey(), e.getValue());
                }
                DbSchema.restartOracleIdentities(c, ids);
            }
        } catch (SQLException e) {
            throw TopologyException.badRequest("기초 데이터 입력 실패 (모두 되돌림" + (kind == DbKind.SYBASE ? " - Sybase 는 들어간 행이 남을 수 있음" : "") + ") : " + e.getMessage());
        }
        List<String> logs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) logs.add(e.getKey() + " " + e.getValue() + "행");
        log.info("DB SETUP DATA {} schema={} {} by account {}", kind.key(), schema, counts, r.getUser());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inserted", counts);
        m.put("file", DbSetupScripts.baseDataFile());
        m.put("log", logs);
        return m;
    }

    // ===================== 접속 =====================

    /** 서버(스키마 밖)에 접속 - 스키마 확인 · 생성용 */
    Connection openServer(DbSetupRequest r, DbKind kind) throws SQLException {
        if (kind == DbKind.H2) {
            String schema = schema(r, kind);
            // 파일이 있으면 그 파일에 (계정 확인), 없으면 메모리 DB 로 드라이버만 확인
            return new File(h2File(r, schema) + ".mv.db").isFile() ? openH2(r, schema, true)
                    : connect(kind, "jdbc:h2:mem:scb_setup_probe", r);
        }
        return connect(kind, serverUrl(r, kind), r);
    }

    /** 스키마 안으로 접속 - 테이블 · 데이터용 */
    Connection openSchema(DbSetupRequest r, DbKind kind) throws SQLException {
        String schema = schema(r, kind);
        switch (kind) {
            case MYSQL:
            case MARIADB:
                return connect(kind, withDatabase(serverUrl(r, kind), schema), r);
            case SYBASE: {
                Connection c = connect(kind, serverUrl(r, kind), r);
                try (Statement st = c.createStatement()) {
                    st.execute("USE " + schema);
                }
                return c;
            }
            case ORACLE: {
                Connection c = connect(kind, serverUrl(r, kind), r);
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema);
                }
                return c;
            }
            default:
                return openH2(r, schema, true);
        }
    }

    private Connection openH2(DbSetupRequest r, String schema, boolean mustExist) throws SQLException {
        String url = "jdbc:h2:file:" + h2File(r, schema) + ";DB_CLOSE_ON_EXIT=FALSE" + (mustExist ? ";IFEXISTS=TRUE" : "");
        return connect(DbKind.H2, url, r);
    }

    private Connection connect(DbKind kind, String url, DbSetupRequest r) throws SQLException {
        try {
            Class.forName(kind.driver());
        } catch (ClassNotFoundException e) {
            throw new SQLException(kind.label() + " JDBC 드라이버가 없습니다 (" + kind.driver() + ")");
        }
        Properties p = new Properties();
        p.setProperty("user", r.getUser() == null ? "" : r.getUser());
        p.setProperty("password", r.getPassword() == null ? "" : r.getPassword());
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SEC);
        return DriverManager.getConnection(url, p);
    }

    /** 스키마 없이 접속하는 주소 (직접 입력한 URL 이 있으면 그대로) */
    String serverUrl(DbSetupRequest r, DbKind kind) {
        if (!blank(r.getUrl())) {
            String u = r.getUrl().trim();
            if (!u.startsWith("jdbc:")) throw TopologyException.badRequest("JDBC URL 은 jdbc: 로 시작해야 합니다.");
            return u;
        }
        String host = blank(r.getHost()) ? "127.0.0.1" : r.getHost().trim();
        if (!HOST.matcher(host).matches()) throw TopologyException.badRequest("호스트 값이 올바르지 않습니다.");
        int port = r.getPort() == null ? defaultPort(kind) : r.getPort();
        if (port < 1 || port > 65535) throw TopologyException.badRequest("포트는 1 ~ 65535 입니다.");
        switch (kind) {
            case ORACLE: {
                String svc = blank(r.getService()) ? "ORCLPDB1" : r.getService().trim();
                if (!SERVICE.matcher(svc).matches()) throw TopologyException.badRequest("Oracle 서비스 이름이 올바르지 않습니다.");
                return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + svc;
            }
            case SYBASE:
                return "jdbc:jtds:sybase://" + host + ":" + port + "/master;charset=utf8;useLOBs=false;appName=scb-db-setup";
            case MYSQL:
                return "jdbc:mysql://" + host + ":" + port + "/?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false";
            case MARIADB:
                return "jdbc:mariadb://" + host + ":" + port + "/";
            default:
                return "jdbc:h2:mem:scb_setup_probe";
        }
    }

    /** MySQL / MariaDB URL 에 database 이름 넣기 (jdbc:mysql://h:p/?a=b → jdbc:mysql://h:p/db?a=b) */
    static String withDatabase(String url, String db) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? "" : url.substring(q);
        int slash = base.indexOf('/', base.indexOf("//") + 2);
        base = slash < 0 ? base + "/" + db : base.substring(0, slash + 1) + db;
        return base + query;
    }

    static int defaultPort(DbKind kind) {
        switch (kind) {
            case ORACLE: return 1521;
            case SYBASE: return 5000;
            case MYSQL: return 3306;
            case MARIADB: return 3306;
            default: return 0;
        }
    }

    // ===================== 확인 =====================

    boolean schemaExists(Connection c, DbKind kind, DbSetupRequest r, String schema) throws SQLException {
        String sql;
        switch (kind) {
            case MYSQL:
            case MARIADB:
                sql = "SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = LOWER(?)";
                break;
            case ORACLE:
                sql = "SELECT COUNT(*) FROM all_users WHERE username = UPPER(?)";
                break;
            case SYBASE:
                sql = "SELECT COUNT(*) FROM master..sysdatabases WHERE name = ?";
                break;
            default:
                return new File(h2File(r, schema) + ".mv.db").isFile();
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 스키마 안에 있는 테이블 (소문자) */
    Set<String> existingTables(Connection c, DbKind kind, String schema) throws SQLException {
        String sql;
        boolean param = true;
        switch (kind) {
            case MYSQL:
            case MARIADB:
                sql = "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(?)";
                break;
            case ORACLE:
                sql = "SELECT table_name FROM all_tables WHERE owner = UPPER(?)";
                break;
            case SYBASE:
                sql = "SELECT name FROM sysobjects WHERE type = 'U'";
                param = false;
                break;
            default:
                sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'";
                param = false;
        }
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param) ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 기초 데이터 테이블의 행 수 (있는 테이블만) */
    private Map<String, Long> baseRows(Connection c, Set<String> have) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        try (Statement st = c.createStatement()) {
            for (String t : DbSetupScripts.baseTables()) {
                if (!have.contains(t)) continue;
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    m.put(t, rs.next() ? rs.getLong(1) : 0L);
                }
            }
        }
        return m;
    }

    private void requireSchema(DbSetupRequest r, DbKind kind, String schema) {
        try (Connection c = openServer(r, kind)) {
            if (!schemaExists(c, kind, r, schema)) {
                throw TopologyException.badRequest("스키마 " + schema + " 가 없습니다 - 먼저 [2. 스키마 생성] 을 실행하세요.");
            }
        } catch (SQLException e) {
            throw TopologyException.badRequest("접속 실패 : " + NamedJdbc.rootMessage(e));
        }
    }

    /** 이 화면이 만드는 테이블 (DDL 순서) */
    public List<String> targetTables() {
        return DbDialect.tableOrder(DbSchema.resource(DbSchema.ANSI));
    }

    // ===================== 입력 값 =====================

    private static DbKind kind(DbSetupRequest r) {
        DbKind k = DbKind.of(r.getKind());
        if (k == null) throw TopologyException.badRequest("DB 종류를 고르세요 (Oracle / Sybase / MySQL / MariaDB / H2).");
        if (blank(r.getUser())) throw TopologyException.badRequest("접속 계정(아이디)을 입력하세요.");
        return k;
    }

    private static String schema(DbSetupRequest r, DbKind kind) {
        String s = ident(blank(r.getSchema()) ? "" : r.getSchema().trim(), "스키마 이름");
        return kind == DbKind.ORACLE ? s.toUpperCase(Locale.ROOT) : s;
    }

    private static String h2File(DbSetupRequest r, String schema) {
        String dir = blank(r.getH2Dir()) ? "./data/setup" : r.getH2Dir().trim();
        if (dir.contains(";") || dir.contains("'") || dir.contains("\"")) throw TopologyException.badRequest("H2 폴더 경로에 ; ' \" 는 쓸 수 없습니다.");
        return new File(dir, schema).getAbsolutePath().replace('\\', '/');
    }

    private static String ident(String v, String what) {
        if (v == null || !IDENT.matcher(v).matches()) {
            throw TopologyException.badRequest(what + " 은(는) 영문으로 시작하는 영문 · 숫자 · _ 30자 이내로 입력하세요.");
        }
        return v;
    }

    private static String secret(String v, String what) {
        if (v.length() > 64 || v.contains("'") || v.contains("\"") || v.contains("\\") || v.contains(";")) {
            throw TopologyException.badRequest(what + " 에는 ' \" \\ ; 를 쓸 수 없습니다 (64자 이내).");
        }
        return v;
    }

    private static void need(String v, String what) {
        if (v == null) throw TopologyException.badRequest(what + " 을(를) 입력하세요.");
    }

    private static void exec(Statement st, List<String> logs, String sql) throws SQLException {
        exec(st, logs, sql, sql);
    }

    /** shown = 화면 · 로그에 남길 문장 (비밀번호 가림) */
    private static void exec(Statement st, List<String> logs, String sql, String shown) throws SQLException {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(NamedJdbc.rootMessage(e) + " - " + shown, e.getSQLState(), e.getErrorCode(), e);
        }
        logs.add(shown);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String abbreviate(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + " ..." : s;
    }
}
