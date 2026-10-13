package com.scb.monitoring.config.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.alarm.mapper.AlarmMapper;
import com.scb.monitoring.serverlog.mapper.ServerLogMapper;
import com.scb.monitoring.svcctl.mapper.ServiceCtlMapper;
import com.scb.monitoring.topology.domain.MetricHist;
import com.scb.monitoring.topology.mapper.TopologyMapper;

/**
 * Sybase ASE 용 매퍼 분기 (ROW_NUMBER 대신 TOP · 최근 24시간 · 상관 하위 질의, CURRENT_TIMESTAMP → getdate()) 를
 * H2 의 MSSQLServer 호환 모드(TOP · getdate · dateadd 지원)에서 실행해 기본 SQL 과 결과 비교.
 */
@MybatisTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sybmapper;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DialectSqlInterceptor.class)
class SybaseMapperTest {

    @Autowired TopologyMapper topology;
    @Autowired ServiceCtlMapper svc;
    @Autowired AgentMapper agent;
    @Autowired AlarmMapper alarm;
    @Autowired ServerLogMapper serverLog;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void reset() {
        DbDialect.setCurrent(DbKind.H2);
    }

    /** 같은 질의를 기본 SQL 과 Sybase 분기로 실행 */
    private <T> List<List<T>> both(Supplier<List<T>> q) {
        DbDialect.setCurrent(DbKind.H2);
        List<T> ansi = q.get();
        DbDialect.setCurrent(DbKind.SYBASE);
        List<T> syb = q.get();
        return Arrays.asList(ansi, syb);
    }

    private static String key(MetricHist h) {
        return h.getServerId() + "/" + h.getProcId() + "/" + h.getCollectDt();
    }

    @Test
    void topNQueriesMatch() {
        for (int i = 0; i < 6; i++) {
            jdbc.update("INSERT INTO tb_server_metric_hist (server_id, collect_dt, cpu_pct, mem_pct, disk_pct, session_cnt, src_cd) "
                    + "VALUES (11, DATEADD(MINUTE, ?, CURRENT_TIMESTAMP), 1.0, 2.0, 3.0, 4, 'AGENT')", -i);
            jdbc.update("INSERT INTO tb_heartbeat_hist (server_id, event_cd, event_dt) VALUES (11, 'INTERVAL', CURRENT_TIMESTAMP)");
        }
        List<List<MetricHist>> m = both(() -> topology.selectMetricHistory(3));
        // Sybase 분기 : 최근 24시간 전체 → 서비스에서 서버별 3건으로 자름 (TopologyService.lastN)
        List<MetricHist> sybTrim = com.scb.monitoring.topology.service.TopologyService.lastN(m.get(1), MetricHist::getServerId, 3);
        List<String> ansi11 = m.get(0).stream().filter(h -> h.getServerId() == 11L).map(SybaseMapperTest::key).collect(Collectors.toList());
        assertEquals(ansi11, sybTrim.stream().map(SybaseMapperTest::key).collect(Collectors.toList()));
        assertEquals(3, ansi11.size());

        List<List<com.scb.monitoring.agent.domain.HeartbeatHist>> hb = both(() -> agent.selectHbHist(11L, 4));
        assertEquals(hb.get(0).stream().map(h -> h.getHistId()).collect(Collectors.toList()),
                hb.get(1).stream().map(h -> h.getHistId()).collect(Collectors.toList()));
        assertEquals(4, hb.get(1).size());

        // 실행 확인 (데이터가 적어 결과는 같아야 함)
        Timestamp since = new Timestamp(System.currentTimeMillis() - 86_400_000L);
        assertSame(both(() -> alarm.selectClosedAlarmList(since, 5)));
        assertSame(both(() -> svc.selectRecentCommands(11L, 5)));
        assertSame(both(() -> svc.selectCommandsByTargets(11L, Arrays.asList("all", "app1"), 5)));
        assertSame(both(() -> serverLog.selectOldSnapshotIds(1L, 2)));
        assertTrue(both(() -> topology.selectProcessHistory(3)).get(1).isEmpty(), "샘플 이력은 24시간보다 오래됨");
        both(() -> svc.selectServiceHistory(3));

        // CURRENT_TIMESTAMP → getdate() (H2 MSSQLServer 모드는 둘 다 됨) : 갱신 SQL 실행 확인
        DbDialect.setCurrent(DbKind.SYBASE);
        assertEquals(DbKind.SYBASE, DbDialect.current());
        jdbc.update("UPDATE tb_server SET upd_dt = CURRENT_TIMESTAMP WHERE server_id = 11");
    }

    private static <T> void assertSame(List<List<T>> r) {
        assertEquals(r.get(0).size(), r.get(1).size(), r.toString());
    }
}
