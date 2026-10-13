package com.scb.monitoring.config.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** DB 간 데이터 이전 : 원본(기본 데이터 + 추가) → 대상(다른 내용) 을 덮어쓰고 건수·값·IDENTITY 다음 값 확인 (H2 메모리 DB 두 개) */
class DbCopyTest {

    private static DriverManagerDataSource h2(String name) throws SQLException {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/schema-ansi.sql"), StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/data.sql"), StandardCharsets.UTF_8));
        }
        return ds;
    }

    @Test
    void copiesAllTablesAndKeepsIdentity() throws Exception {
        DriverManagerDataSource src = h2("copy_src");
        DriverManagerDataSource dst = h2("copy_dst");
        JdbcTemplate s = new JdbcTemplate(src);
        JdbcTemplate d = new JdbcTemplate(dst);
        // 원본에만 있는 서버 (큰 id) / 대상에만 있는 서버 (지워져야 함)
        s.update("INSERT INTO tb_server (server_id, system_nm, fqdn, ip_addr, os_type, status_cd, switch_node_id) VALUES (500, 'COPY-SRC', 'copy-src', '10.0.0.5', 'SOLARIS', 'Active', 10)");
        d.update("INSERT INTO tb_server (server_id, system_nm, fqdn, ip_addr, os_type, status_cd, switch_node_id) VALUES (600, 'ONLY-DST', 'only-dst', '10.0.0.6', 'SOLARIS', 'Active', 10)");

        List<DbCopy.Result> rs = DbCopy.copy(src, dst);

        assertTrue(rs.stream().allMatch(DbCopy.Result::ok), rs.toString());
        assertTrue(rs.size() >= 18, "tables " + rs.size());
        assertEquals(s.queryForObject("SELECT COUNT(*) FROM tb_server", Integer.class), d.queryForObject("SELECT COUNT(*) FROM tb_server", Integer.class));
        assertEquals("COPY-SRC", d.queryForObject("SELECT system_nm FROM tb_server WHERE server_id = 500", String.class));
        assertEquals(0, d.queryForObject("SELECT COUNT(*) FROM tb_server WHERE server_id = 600", Integer.class));
        // IDENTITY 다음 값이 복사한 최댓값 뒤로 맞춰졌는지 (새로 넣어도 충돌 없음)
        d.update("INSERT INTO tb_server (system_nm, fqdn, ip_addr, os_type, status_cd, switch_node_id) VALUES ('NEW', 'new', '10.0.0.7', 'SOLARIS', 'Active', 10)");
        Long newId = d.queryForObject("SELECT server_id FROM tb_server WHERE system_nm = 'NEW'", Long.class);
        assertTrue(newId > 500, "new id " + newId);
    }

    @Test
    void platformNames() {
        String p = EmbeddedMariaDb.platform();
        assertTrue(p.equals("windows-x64") || p.equals("linux-x64") || p.startsWith("solaris-"), p);
    }
}
