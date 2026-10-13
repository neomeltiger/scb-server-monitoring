package com.scb.monitoring.dbsetup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scb.monitoring.topology.service.TopologyException;

/** 신규 DB 초기 구성 : H2 파일 DB 로 연결 확인 → 스키마 → 테이블 (없는 것만) → 기초 데이터 (비었을 때만) */
class DbSetupServiceTest {

    private final DbSetupService svc = new DbSetupService();

    private DbSetupRequest req(File dir) {
        DbSetupRequest r = new DbSetupRequest();
        r.setKind("h2");
        r.setH2Dir(dir.getAbsolutePath());
        r.setUser("sa");
        r.setPassword("setup-pw");
        r.setSchema("scb_new");
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullFlowOnH2() throws Exception {
        File dir = Files.createTempDirectory("scb-setup").toFile();
        DbSetupRequest r = req(dir);

        Map<String, Object> t = svc.test(r);
        assertEquals(Boolean.FALSE, t.get("schemaExists"));
        assertThrows(TopologyException.class, () -> svc.createTables(r), "스키마 없으면 테이블 생성 불가");

        Map<String, Object> s = svc.createSchema(r);
        assertEquals(Boolean.TRUE, s.get("created"));
        assertTrue(new File(dir, "scb_new.mv.db").isFile());
        assertEquals(Boolean.FALSE, svc.createSchema(r).get("created"), "스키마가 있으면 건너뜀");

        // 스키마가 있으면 테이블만 생성
        Map<String, Object> tb = svc.createTables(r);
        List<String> created = (List<String>) tb.get("created");
        assertEquals(svc.targetTables().size(), created.size(), "전체 테이블 생성");
        Map<String, Object> again = svc.createTables(r);
        assertTrue(((List<String>) again.get("created")).isEmpty(), "두 번째는 만들 것 없음");
        assertEquals(created.size(), ((List<String>) again.get("skipped")).size());

        Map<String, Object> d = svc.insertBaseData(r);
        Map<String, Integer> ins = (Map<String, Integer>) d.get("inserted");
        assertEquals(1, ins.get("tb_user").intValue());
        assertTrue(ins.get("tb_menu") > 10);
        assertTrue(ins.containsKey("tb_server") && ins.containsKey("tb_retention"));
        assertFalse(ins.containsKey("tb_server_metric_hist"), "이력 제외");
        assertThrows(TopologyException.class, () -> svc.insertBaseData(r), "기초 데이터가 있으면 다시 넣지 않음");

        Map<String, Object> t2 = svc.test(r);
        assertEquals(Boolean.TRUE, t2.get("schemaExists"));
        assertEquals(svc.targetTables().size(), ((List<String>) t2.get("existingTables")).size());
        assertEquals(1L, ((Map<String, Long>) t2.get("baseRows")).get("tb_user").longValue());

        // 지금 연결된 DB 확인 (웹의 DataSource 로) : 테이블 · 기초 데이터 모두 있음
        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:file:" + new File(dir, "scb_new").getAbsolutePath().replace('\\', '/') + ";IFEXISTS=TRUE", "sa", "setup-pw");
        Map<String, Object> cur = svc.checkCurrent(ds);
        assertEquals(Boolean.TRUE, cur.get("connected"));
        assertEquals("h2", cur.get("kind"));
        assertEquals("OK", cur.get("verdict"));
        assertTrue(((List<String>) cur.get("missing")).isEmpty());
        assertEquals("jdbc:x:y;password=*****;a=1", DbSetupService.maskUrl("jdbc:x:y;password=secret;a=1"));

        // 틀린 비밀번호
        r.setPassword("wrong");
        assertThrows(TopologyException.class, () -> svc.test(r));
    }

    @Test
    void validatesInput() throws Exception {
        File dir = Files.createTempDirectory("scb-setup").toFile();
        DbSetupRequest r = req(dir);
        r.setSchema("bad name;drop");
        assertThrows(TopologyException.class, () -> svc.test(r));
        r.setSchema("scb_x");
        r.setKind("db2");
        assertThrows(TopologyException.class, () -> svc.test(r));
        r.setKind("mysql");
        r.setHost("evil;host");
        assertThrows(TopologyException.class, () -> svc.test(r));
        assertEquals("jdbc:mysql://h:3306/scb?x=1", DbSetupService.withDatabase("jdbc:mysql://h:3306/?x=1", "scb"));
        assertEquals("jdbc:mariadb://h:3306/scb", DbSetupService.withDatabase("jdbc:mariadb://h:3306/", "scb"));
    }
}
