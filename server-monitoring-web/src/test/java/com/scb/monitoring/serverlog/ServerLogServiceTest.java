package com.scb.monitoring.serverlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.dto.LogSourceForm;
import com.scb.monitoring.serverlog.dto.ServerLogView;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/** 서버 로그 주기 요청 (seed: server 11 = 로그 2건, 주기 5분, 아직 요청 안 함) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({ServerLogService.class, AgentService.class, ServiceCtlService.class, AlarmService.class, TopologyService.class, com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class})
@EnableConfigurationProperties(AgentProperties.class)
class ServerLogServiceTest {

    @Autowired ServerLogService logs;
    @Autowired AgentService agent;
    @Autowired TopologyService topology;
    @Autowired JdbcTemplate jdbc;

    private static long lines(String s, String prefix) {
        return s.isEmpty() ? 0 : java.util.Arrays.stream(s.split("\n")).filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    void requestsOnScheduleAndOnDemand() {
        String first = agent.pullCommands(11L);
        assertEquals(2, lines(first, "log|"), "처음에는 바로 요청");
        assertTrue(first.contains("|200|/var/adm/messages\n"));
        ServerLogView v = logs.getServerLogs(11L);
        assertTrue(v.getPolicy().getNextReqDt() != null, "다음 예정 = 지금 + 5분");

        assertEquals(0, lines(agent.pullCommands(11L), "log|"), "주기 전에는 요청 안 함");

        logs.requestNow(11L, "admin");
        assertTrue(logs.getServerLogs(11L).getPolicy().isRequestPending());
        assertEquals(2, lines(agent.pullCommands(11L), "log|"), "[지금 요청] 은 바로 전달");
        assertFalse(logs.getServerLogs(11L).getPolicy().isRequestPending());

        logs.changeInterval(11L, 0, "admin");
        assertEquals(0, lines(agent.pullCommands(11L), "log|"), "사용 안 함");
        assertNull(logs.getServerLogs(11L).getPolicy().getNextReqDt());

        logs.changeInterval(11L, 1, "admin");
        assertEquals(2, lines(agent.pullCommands(11L), "log|"), "주기 변경 후 바로 1회");
        assertThrows(TopologyException.class, () -> logs.changeInterval(11L, 7, "admin"));
    }

    @Test
    void serverWithoutSourcesIsNeverAsked() {
        assertEquals("", logs.pullLogRequests(12L));
        assertThrows(TopologyException.class, () -> logs.requestNow(12L, "admin"));
    }

    @Test
    void ingestKeepsLatest20AndChecksOwner() {
        Long logId = logs.getServerLogs(11L).getSources().get(0).getId();
        assertFalse(logs.ingestLog(12L, logId, "OK", "x"), "다른 서버의 로그 ID 는 거부");
        for (int i = 1; i <= 25; i++) {
            assertTrue(logs.ingestLog(11L, logId, i == 25 ? "WEIRD" : "OK", "line a " + i + "\nline b\n"));
        }
        List<ServerLogView.Snapshot> list = logs.getSnapshots(11L, logId);
        assertEquals(ServerLogService.KEEP_SNAPSHOTS, list.size());
        ServerLogView.Snapshot latest = logs.getSnapshot(11L, list.get(0).getId());
        assertEquals("ERROR", latest.getStatus(), "모르는 상태값은 ERROR");
        assertEquals(2, latest.getLineCnt());
        assertTrue(latest.getContent().startsWith("line a 25"));
        assertEquals("ERROR", logs.getServerLogs(11L).getSources().get(0).getLastStatus());
        assertTrue(logs.getServerLogs(11L).getPolicy().getLastRecvDt() != null);
        assertThrows(TopologyException.class, () -> logs.getSnapshot(12L, list.get(0).getId()));
    }

    @Test
    void sourceValidation() {
        LogSourceForm f = new LogSourceForm();
        f.setName("앱 로그");
        f.setPath("/shared/opt/app/log/app.log");
        f.setTailLines(100);
        ServerLogView.Source s = logs.addSource(12L, f);
        assertEquals(100, s.getTailLines());
        assertThrows(TopologyException.class, () -> logs.addSource(12L, f), "같은 경로 중복");
        for (String bad : new String[] {"/var/log/../../etc/shadow", "relative/app.log", "/var/log/", "/var//log/a", "/tmp/a;rm"}) {
            f.setPath(bad);
            assertThrows(TopologyException.class, () -> logs.addSource(12L, f), bad);
        }
        logs.deleteSource(12L, s.getId());
        assertTrue(logs.getServerLogs(12L).getSources().isEmpty());
    }

    @Test
    void deleteServerRemovesLogData() {
        Long logId = logs.getServerLogs(11L).getSources().get(0).getId();
        logs.ingestLog(11L, logId, "OK", "hello\n");
        topology.deleteServer(11L);
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_log_snapshot", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_log_source", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_log_policy", Integer.class));
    }
}
