package com.scb.monitoring.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;

import com.scb.monitoring.agent.domain.HeartbeatHist;
import com.scb.monitoring.agent.dto.HeartbeatView;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.agent.service.HeartbeatService;
import com.scb.monitoring.agent.service.ProcessStatusService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.dto.ProcessView;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/**
 * 하트비트 : 수신 → 끊김(LOST) 감시 → 감시 프로세스 즉시 종료 표시 → 복구, 데몬 종료 보고, 주기 변경.
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql,classpath:db/users.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AgentService.class, HeartbeatService.class, ServiceCtlService.class, TopologyService.class, AlarmService.class,
        ProcessStatusService.class, com.scb.monitoring.serverlog.service.ServerLogService.class,
        com.scb.monitoring.ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AgentProperties.class)
class HeartbeatTest {

    @Autowired AgentService agent;
    @Autowired HeartbeatService heartbeat;
    @Autowired TopologyService topology;
    @Autowired AlarmService alarm;
    @Autowired DataSource dataSource;
    @Autowired SqlSessionTemplate sqlSession;

    private static final String REPORT = "[sys]\nhostname=krcasmrx11\nos=SunOS 5.10\nagent_ver=1.4\n"
            + "[metric]\ncpu=10\nmem=20\ndisk=30\nsessions=1\n[proc]\n1|Y|100|1|1\n2|Y|200|1|1\n3|Y|300|1|1\n";

    private HeartbeatView view(long serverId) {
        return heartbeat.getStatusList().stream().filter(v -> v.getServerId() == serverId).findFirst().get();
    }

    private List<String> events(long serverId) {
        return heartbeat.getEvents(serverId, 50).stream().map(HeartbeatHist::getEventCd).collect(Collectors.toList());
    }

    private boolean running(long procId) {
        return topology.getServer(11L).getProcesses().stream().filter(p -> p.getId() == procId).findFirst()
                .map(ProcessView::isRunning).get();
    }

    /** 마지막 하트비트를 sec 초 전으로 (시간 경과 흉내) */
    private void ageHeartbeat(int sec) {
        sql("UPDATE tb_agent SET last_hb_dt = DATEADD('SECOND', " + (-sec) + ", CURRENT_TIMESTAMP) WHERE server_id = 11");
    }

    /** 테스트 트랜잭션 안에서 직접 수정 - MyBatis 1차 캐시(같은 트랜잭션의 같은 조회)를 비워 다시 읽게 함 */
    private void sql(String update) {
        new JdbcTemplate(dataSource).update(update);
        sqlSession.clearCache();
    }

    @Test
    void beatLostRecover() {
        agent.issueToken(11L, "admin");
        assertEquals("NONE", view(11).getHbState(), "하트비트 받기 전");

        String resp = heartbeat.beat(11L, 4321);
        assertEquals("hb|interval=10|timeout=32|restart=N\n", resp);
        agent.ingestReport(11L, REPORT, "127.0.0.1");
        HeartbeatView v = view(11);
        assertEquals("ALIVE", v.getHbState());
        assertTrue(v.isHbClient());
        assertEquals("OK", v.getAgentState());
        assertEquals(Integer.valueOf(4321), v.getHbPid());
        assertTrue(events(11).contains("START"));
        assertTrue(running(1L));

        // 2회 미수신(기준 10 x 3 + 2 = 32초 안)이면 그대로
        ageHeartbeat(25);
        assertEquals(0, heartbeat.watch());
        assertTrue(running(1L));
        assertEquals(Integer.valueOf(2), view(11).getHbMisses());
        assertFalse(view(11).isServerDown());

        // 3회 연속 미수신 → 서버 다운 : 감시 프로세스 즉시 종료 표시, 서버 화면 상태 Down
        ageHeartbeat(40);
        assertEquals(1, heartbeat.watch());
        assertEquals("LOST", view(11).getHbState());
        assertEquals("LOST", view(11).getAgentState());
        assertTrue(view(11).isServerDown());
        assertEquals(Integer.valueOf(3), view(11).getHbMisses());
        assertFalse(running(1L));
        assertFalse(running(2L));
        assertEquals("Down", topology.getServer(11L).getStatus());
        assertEquals("하트비트 끊김", topology.getServer(11L).getNoData());
        assertEquals("LOST", events(11).get(0));
        assertEquals(0, heartbeat.watch(), "이미 끊김이면 다시 기록하지 않음");

        // 다시 수신 → 복구
        heartbeat.beat(11L, 4321);
        assertEquals("ALIVE", view(11).getHbState());
        assertEquals("RECOVER", events(11).get(0));
        agent.ingestReport(11L, REPORT, "127.0.0.1");
        assertTrue(running(1L), "다음 보고로 실제 상태 복귀");
    }

    @Test
    void daemonStoppedStopsProcessesImmediately() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 555, "1.4");
        heartbeat.beat(11L, 555);
        agent.ingestReport(11L, REPORT, "127.0.0.1");
        assertTrue(running(3L));

        agent.daemonStopped(11L);
        assertFalse(running(3L));
        assertEquals("STOPPED", view(11).getHbState());
        assertEquals("STOPPED", view(11).getAgentState());
        assertEquals("STOP", events(11).get(0));
        assertEquals(0, heartbeat.watch(), "데몬 종료는 끊김(LOST)으로 다시 처리하지 않음");
        assertEquals("Active", topology.getServer(11L).getStatus(), "데몬 종료 보고 = 서버는 정상");
        assertEquals("데몬 종료", topology.getServer(11L).getDaemonIssue());
    }

    @Test
    void watchdogDaemonDeadIsNotServerDown() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 900, "1.5");
        heartbeat.beat(11L, 900, "RUNNING");
        agent.ingestReport(11L, REPORT, "127.0.0.1");
        assertTrue(running(1L));
        assertTrue(view(11).isWatchdog());
        assertFalse(view(11).isCanRestart() && view(11).getDaemonIssue() != null);

        // 감시자가 데몬 비정상 종료를 알림 → 서버는 정상, 프로세스는 종료, 데몬 중단 알람
        assertEquals("hb|interval=10|timeout=32|restart=N\n", heartbeat.beat(11L, 900, "DEAD"));
        HeartbeatView v = view(11);
        assertEquals("ALIVE", v.getHbState());
        assertEquals("STOPPED", v.getAgentState());
        assertFalse(v.isServerDown());
        assertEquals("데몬 비정상 종료", v.getDaemonIssue());
        assertTrue(v.isCanRestart());
        assertEquals("Active", topology.getServer(11L).getStatus());
        assertEquals(null, topology.getServer(11L).getNoData());
        assertFalse(running(1L));
        assertEquals("D_DOWN", events(11).get(0));
        alarm.checkAll();
        assertTrue(alarm.getActive().stream().anyMatch(x -> x.getServerId() == 11L && "DAEMON_DOWN".equals(x.getAlarmType())));
        assertFalse(alarm.getActive().stream().anyMatch(x -> x.getServerId() == 11L && "AGENT_STALE".equals(x.getAlarmType())));

        // 웹 재시작 요청 → 다음 하트비트 응답 restart=Y (한 번만)
        assertTrue(heartbeat.requestRestart(11L, "admin").isRestartPending());
        assertEquals("RESTART", events(11).get(0));
        assertEquals("hb|interval=10|timeout=32|restart=Y\n", heartbeat.beat(11L, 900, "DEAD"));
        assertEquals("hb|interval=10|timeout=32|restart=N\n", heartbeat.beat(11L, 900, "DEAD"));

        // 데몬이 다시 뜸 : 새 데몬의 웹 확인 → 바로 D_UP (감시자의 다음 하트비트보다 먼저 와도), 첫 보고가 종료로 덮이지 않음
        agent.pullCommands(11L, "RUNNING", 901, "1.5");
        assertEquals("D_UP", events(11).get(0));
        agent.ingestReport(11L, REPORT, "127.0.0.1");
        alarm.checkAll();
        assertTrue(running(1L), "재시작 후 첫 보고 반영");
        heartbeat.beat(11L, 901, "RUNNING");
        assertEquals("D_UP", events(11).get(0));
        assertEquals(1, events(11).stream().filter("D_UP"::equals).count(), "D_UP 은 한 번만");
        assertFalse(view(11).isRestartPending());
        assertEquals(null, view(11).getDaemonIssue());
        alarm.checkAll();
        assertFalse(alarm.getActive().stream().anyMatch(x -> x.getServerId() == 11L && "DAEMON_DOWN".equals(x.getAlarmType())));
        assertThrows(TopologyException.class, () -> heartbeat.requestRestart(11L, "admin"), "데몬 실행 중이면 재시작 요청 불가");

        // 감시자까지 끊기면(서버 다운) 그때 Down
        ageHeartbeat(40);
        assertEquals(1, heartbeat.watch());
        assertEquals("Down", topology.getServer(11L).getStatus());
        assertThrows(TopologyException.class, () -> heartbeat.requestRestart(11L, "admin"), "하트비트가 끊기면 재시작 명령 전달 불가");
    }

    @Test
    void legacyDaemonUsesPollAsHeartbeat() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 777, "1.3");        // 하트비트 셸 없는 이전 데몬
        HeartbeatView v = view(11);
        assertEquals("ALIVE", v.getHbState());
        assertFalse(v.isHbClient());
        assertEquals(45, v.getHbTimeoutSec());
        assertTrue(events(11).contains("START"));

        sql("UPDATE tb_agent SET last_poll_dt = DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE server_id = 11");
        assertEquals(1, heartbeat.watch());
        assertEquals("LOST", view(11).getHbState());
        assertFalse(running(1L));
    }

    @Test
    void changeInterval() {
        assertThrows(TopologyException.class, () -> heartbeat.changeInterval(11L, 5, "admin"), "토큰 발급 전");
        agent.issueToken(11L, "admin");
        assertThrows(TopologyException.class, () -> heartbeat.changeInterval(11L, 7, "admin"), "허용 값 아님");

        assertEquals(5, heartbeat.changeInterval(11L, 5, "admin").getHbIntervalSec());
        assertEquals("hb|interval=5|timeout=17|restart=N\n", heartbeat.beat(11L, 1));
        assertTrue(heartbeat.getEvents(11L, 10).stream().anyMatch(h -> "INTERVAL".equals(h.getEventCd())
                && h.getDetail().startsWith("10초 → 5초")));

        // 서버가 없는 하트비트 이벤트 조회 (전체)
        assertFalse(heartbeat.getEvents(null, 10).isEmpty());
    }
}
