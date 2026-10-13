package com.scb.monitoring.config.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 주 DB 자동 교체 · 관리자 전환 · 설정 파일 (H2 메모리 DB 를 MySQL / MariaDB 자리에 넣어 시험).
 * Oracle / Sybase 변환 DDL·데이터는 H2 의 Oracle / MSSQLServer 호환 모드에서 실행해 확인.
 */
class DbManagerTest {

    @TempDir
    File tmp;

    private DbManager manager;

    @AfterEach
    void close() {
        if (manager != null) manager.destroy();
        DbDialect.setCurrent(DbKind.H2);
    }

    private static void create(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            for (String s : new String[] {"db/schema-ansi.sql", "db/data.sql", "db/users.sql"}) {
                ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(s), StandardCharsets.UTF_8));
            }
        }
    }

    private static void shutdown(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";IFEXISTS=TRUE", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        }
    }

    private DbSettingsStore store(String a, String b, boolean failover) throws Exception {
        File f = new File(tmp, "scb-db.properties");
        String conn = ";IFEXISTS=TRUE";
        Files.write(f.toPath(), Arrays.asList(
                "db.primary=auto", "db.failover=" + failover, "db.health-interval-sec=600",
                "db.oracle.enabled=false", "db.sybase.enabled=false", "db.h2.enabled=false",
                "db.mysql.enabled=true", "db.mysql.url=jdbc:h2:mem:" + a + conn, "db.mysql.driver=org.h2.Driver",
                "db.mysql.username=sa", "db.mysql.connection-timeout=1000",
                "db.mariadb.enabled=true", "db.mariadb.embedded=false", "db.mariadb.url=jdbc:h2:mem:" + b + conn,
                "db.mariadb.driver=org.h2.Driver", "db.mariadb.username=sa", "db.mariadb.connection-timeout=1000"), StandardCharsets.UTF_8);
        return new DbSettingsStore(new DbProperties(), f.getAbsolutePath());
    }

    @Test
    void failoverToNextAndSwitchBackWithCopy() throws Exception {
        create("fo_a");
        create("fo_b");
        manager = new DbManager(store("fo_a", "fo_b", true), null, null).start();
        assertEquals(DbKind.MYSQL, manager.currentKind(), "우선순위 : Oracle·Sybase 꺼짐 → MySQL");
        JdbcTemplate j = new JdbcTemplate(manager.getDataSource());
        j.update("UPDATE tb_server SET system_nm = 'ON-A' WHERE server_id = 11");

        shutdown("fo_a");                                    // MySQL 연결 끊김
        try {
            j.queryForObject("SELECT COUNT(*) FROM tb_server", Integer.class);     // 쓰던 연결 : 실패할 수 있음 → 뒤에서 교체
        } catch (RuntimeException expected) {
            // 끊긴 연결
        }
        waitFor(() -> manager.currentKind() == DbKind.MARIADB);
        assertEquals(10, j.queryForObject("SELECT COUNT(*) FROM tb_server", Integer.class), "다음 요청은 교체된 DB 로");
        assertEquals(DbKind.MARIADB, DbDialect.current());
        assertTrue(manager.events().get(0).type.equals("FAILOVER"), manager.events().get(0).message);
        j.update("UPDATE tb_server SET system_nm = 'ON-B' WHERE server_id = 11");

        // 관리자 화면 : MySQL 이 다시 살아나면 '연결 가능' + 데이터 복사 후 전환
        create("fo_a");
        List<DbManager.Status> st = manager.statuses();
        assertEquals(Arrays.asList("oracle", "sybase", "mysql", "mariadb", "h2"), st.stream().map(s -> s.key).collect(java.util.stream.Collectors.toList()));
        assertTrue(st.get(2).ok && !st.get(2).current, st.get(2).message);
        assertFalse(st.get(0).ok, "꺼진 DB 는 선택 불가");
        DbManager.SwitchResult r = manager.switchTo(DbKind.MYSQL, true, "admin");
        assertTrue(r.copied.stream().allMatch(DbCopy.Result::ok), r.copied.toString());
        assertEquals(DbKind.MYSQL, manager.currentKind());
        assertEquals("ON-B", j.queryForObject("SELECT system_nm FROM tb_server WHERE server_id = 11", String.class));
        assertThrows(IllegalArgumentException.class, () -> manager.switchTo(DbKind.MYSQL, false, "admin"), "이미 사용 중");
        assertThrows(IllegalArgumentException.class, () -> manager.switchTo(DbKind.ORACLE, false, "admin"), "꺼진 DB");
    }

    private static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 100 && !ok.getAsBoolean(); i++) Thread.sleep(100);
        assertTrue(ok.getAsBoolean(), "10초 안에 바뀌지 않음");
    }

    @Test
    void noFailoverWhenDisabled() throws Exception {
        create("nf_a");
        create("nf_b");
        manager = new DbManager(store("nf_a", "nf_b", false), null, null).start();
        JdbcTemplate j = new JdbcTemplate(manager.getDataSource());
        shutdown("nf_a");
        assertThrows(Exception.class, () -> j.queryForObject("SELECT COUNT(*) FROM tb_server", Integer.class));
        waitFor(() -> "FAIL".equals(manager.events().get(0).type));
        assertEquals(DbKind.MYSQL, manager.currentKind(), "자동 교체 꺼짐 → 그대로");
    }

    @Test
    void startFailsWhenNothingUsable() throws Exception {
        DbSettingsStore s = store("none_a", "none_b", true);           // 두 DB 모두 없음 (IFEXISTS)
        assertThrows(IllegalStateException.class, () -> new DbManager(s, null, null).start());
    }

    @Test
    void settingsFileKeepsCommentsAndEncryptsPassword() throws Exception {
        DbSettingsStore s = new DbSettingsStore(new DbProperties(), new File(tmp, "conf/scb-db.properties").getPath());
        s.saveConn(DbKind.ORACLE, true, null, "jdbc:oracle:thin:@//10.0.0.20:1521/ORCL", "scbmon", "p@ss-1", false);
        s.saveGeneral("sybase", false, Arrays.asList(DbKind.H2, DbKind.MYSQL));
        String text = new String(Files.readAllBytes(s.getFile().toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("# 주 DB : auto"), "템플릿 주석 유지");
        assertFalse(text.contains("p@ss-1"), "비밀번호 평문 없음");
        assertTrue(text.contains("db.oracle.password=ENC("));
        assertTrue(new File(tmp, "conf/scb-db.key").isFile());

        DbSettings r = new DbSettingsStore(new DbProperties(), s.getFile().getPath()).get();
        assertEquals("p@ss-1", r.conn(DbKind.ORACLE).getPassword());
        assertTrue(r.conn(DbKind.ORACLE).isEnabled());
        assertEquals(DbKind.SYBASE, r.fixedPrimary());
        assertFalse(r.isFailover());
        assertEquals(Arrays.asList(DbKind.H2, DbKind.MYSQL, DbKind.ORACLE, DbKind.SYBASE, DbKind.MARIADB), r.getOrder());

        s.saveConn(DbKind.ORACLE, false, null, null, null, null, true);
        DbSettings r2 = s.get();
        assertFalse(r2.conn(DbKind.ORACLE).isEnabled());
        assertFalse(r2.conn(DbKind.ORACLE).isPasswordInFile());
    }

    /** schema-ansi.sql / data.sql / users.sql → Oracle · Sybase 문장 : H2 호환 모드에서 실행, 건수가 원본과 같아야 함 */
    @Test
    void oracleAndSybaseScriptsRun() throws Exception {
        String ansi = DbSchema.resource(DbSchema.ANSI);
        Map<String, String> ids = DbDialect.identityColumns(ansi);
        assertEquals("server_id", ids.get("tb_server"));
        create("conv_ref");
        JdbcTemplate ref = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:conv_ref", "sa", ""));
        for (Object[] m : new Object[][] {{DbKind.ORACLE, "Oracle"}, {DbKind.SYBASE, "MSSQLServer"}}) {
            DbKind k = (DbKind) m[0];
            List<String> ddl = DbDialect.ddl(ansi, k);
            assertTrue(ddl.stream().noneMatch(x -> x.startsWith("DROP")), "빈 DB 에만 실행 - DROP 없음");
            String joined = String.join("\n", ddl);
            if (k == DbKind.ORACLE) {
                assertTrue(joined.contains("NUMBER(19) GENERATED BY DEFAULT AS IDENTITY"), joined);
                assertTrue(joined.contains("VARCHAR2(100 CHAR)"));
            } else {
                assertTrue(joined.contains("server_id       BIGINT IDENTITY"), joined);
                assertTrue(joined.contains("DATETIME") && joined.contains("getdate()") && !joined.contains("TIMESTAMP"));
                assertTrue(joined.contains("sync_status     VARCHAR(150) NULL"), "NULL 명시 · 길이 3배");
            }
            org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:conv_" + k.key() + ";MODE=" + m[1] + ";DB_CLOSE_DELAY=-1", "sa", "");
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                for (String sql : ddl) st.execute(sql);
                List<String> data = new java.util.ArrayList<>(DbDialect.data(DbSchema.resource("db/data.sql"), k, ids));
                data.addAll(DbDialect.data(DbSchema.resource("db/users.sql"), k, ids));
                if (k == DbKind.SYBASE) assertTrue(data.contains("SET IDENTITY_INSERT tb_server ON"), "IDENTITY 값 직접 넣기");
                for (String sql : data) if (!sql.startsWith("SET IDENTITY_INSERT")) st.execute(sql);   // H2 에는 없는 문장
            }
            JdbcTemplate t = new JdbcTemplate(ds);
            for (String table : DbDialect.tableOrder(ansi)) {
                assertEquals(ref.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                        t.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), k + " " + table);
            }
        }
    }

    @Test
    void rewriteForSybaseOnly() {
        String sql = "UPDATE tb_x SET upd_dt = CURRENT_TIMESTAMP WHERE a = 1";
        assertEquals("UPDATE tb_x SET upd_dt = getdate() WHERE a = 1", DbDialect.rewrite(sql, DbKind.SYBASE));
        assertEquals(sql, DbDialect.rewrite(sql, DbKind.ORACLE));
        assertNull(DbKind.of("db2"));
        assertEquals(DbKind.MARIADB, DbKind.of(" MariaDB "));
    }
}
