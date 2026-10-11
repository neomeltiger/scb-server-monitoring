package com.scb.monitoring.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.dto.AlarmView;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.svcctl.dto.CommandForm;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/** krcasmrx11_client 데몬 : heartbeat, 웹 제어(시작/중지/종료), 수집 주기 (seed: server 11 = 로그 2건) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AgentService.class, ServiceCtlService.class, TopologyService.class, AlarmService.class, ServerLogService.class, com.scb.monitoring.ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AgentProperties.class)
class AgentDaemonTest {

    @Autowired AgentService agent;
    @Autowired ServiceCtlService svc;
    @Autowired AlarmService alarms;
    @Autowired JdbcTemplate jdbc;

    private static String firstLine(String s) { return s.split("\n")[0]; }

    private static long count(String s, String prefix) {
        return Arrays.stream(s.split("\n")).filter(l -> l.startsWith(prefix)).count();
    }

    private static CommandForm cmd(String target, String c) {
        CommandForm f = new CommandForm();
        f.setTarget(target);
        f.setCmd(c);
        return f;
    }

    @Test
    void heartbeatAndWebControl() {
        agent.issueToken(11L, "admin");
        String r = agent.pullCommands(11L, "RUNNING", 4321, "1.2");
        assertEquals("ctl|collect=on|interval=60|shutdown=N", firstLine(r));
        assertEquals(2, count(r, "log|"), "수집 중이면 로그 요청 전달");
        AgentView v = agent.getAgent(11L);
        assertEquals("RUNNING", v.getDaemonState());
        assertEquals(Integer.valueOf(4321), v.getDaemonPid());
        assertNotNull(v.getDaemonStartDt());
        assertEquals("1.2", v.getAgentVer());

        // 중지 : collect=off, 로그 요청 안 보냄, 서비스 명령은 계속
        agent.controlDaemon(11L, "stop", "admin");
        jdbc.update("UPDATE tb_log_policy SET req_now_yn = 'Y' WHERE server_id = 11");
        svc.requestCommand(11L, cmd("SCB_fs_app1", "status"), "admin");
        r = agent.pullCommands(11L, "PAUSED", 4321, "1.2");
        assertEquals("ctl|collect=off|interval=60|shutdown=N", firstLine(r));
        assertEquals(0, count(r, "log|"));
        assertEquals(1, count(r, "cmd|"));
        assertEquals("PAUSED", agent.getAgent(11L).getState());
        assertTrue(agent.buildConfig(11L).contains("collect=off"));

        // 수집 주기
        agent.changeCollectInterval(11L, 300, "admin");
        assertTrue(firstLine(agent.pullCommands(11L, "PAUSED", 4321, null)).contains("interval=300"));
        assertTrue(agent.buildConfig(11L).contains("interval=300"));
        assertThrows(TopologyException.class, () -> agent.changeCollectInterval(11L, 7, "admin"));
        assertEquals(900, agent.getAgent(11L).getStaleLimitSec(), "보고 끊김 기준 = max(300, 주기 x3)");

        // 시작
        agent.controlDaemon(11L, "start", "admin");
        assertTrue(firstLine(agent.pullCommands(11L, "RUNNING", 4321, null)).contains("collect=on"));
        assertEquals("admin", agent.getAgent(11L).getCtlUser());
        assertThrows(TopologyException.class, () -> agent.controlDaemon(11L, "reboot", "admin"));
        assertThrows(TopologyException.class, () -> agent.controlDaemon(12L, "stop", "admin"), "토큰 없는 서버");
    }

    @Test
    void shutdownIsDeliveredOnceAndRestartWorks() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 100, "1.2");
        agent.controlDaemon(11L, "shutdown", "admin");
        assertTrue(agent.getAgent(11L).isShutdownPending());

        // 이전 cron 클라이언트(데몬 정보 없음)에는 종료를 보내지 않음
        assertTrue(firstLine(agent.pullCommands(11L)).endsWith("shutdown=N"));
        svc.requestCommand(11L, cmd("SCB_fs_app1", "status"), "admin");

        String r = agent.pullCommands(11L, "RUNNING", 100, "1.2");
        assertEquals("ctl|collect=on|interval=60|shutdown=Y", firstLine(r));
        assertEquals(0, count(r, "cmd|"), "종료하는 데몬에는 명령을 주지 않음 (다음 데몬이 받음)");
        assertFalse(agent.getAgent(11L).isShutdownPending(), "한 번만 전달");
        assertEquals("STOPPED", agent.getAgent(11L).getState(), "종료 중");

        agent.daemonStopped(11L);
        AgentView v = agent.getAgent(11L);
        assertEquals("STOPPED", v.getDaemonState());

        // 서버에서 다시 시작 (새 PID)
        String r2 = agent.pullCommands(11L, "RUNNING", 200, "1.2");
        assertTrue(firstLine(r2).endsWith("shutdown=N"));
        assertEquals(1, count(r2, "cmd|"), "남겨 둔 명령은 새 데몬이 받음");
        v = agent.getAgent(11L);
        assertEquals("RUNNING", v.getDaemonState());
        assertEquals(Integer.valueOf(200), v.getDaemonPid());
    }

    @Test
    void servicesByServerForMainHome() {
        java.util.Map<Long, java.util.List<com.scb.monitoring.svcctl.dto.ServiceView>> m = svc.getServicesByServer();
        assertEquals(27, m.get(11L).size(), "krcasmrx11 service.list 27건");
        assertEquals("SCB_fs_app1", m.get(11L).get(0).getName(), "service.list 순서");
        assertFalse(m.containsKey(12L), "서비스 없는 서버는 없음");
    }

    @Test
    void intentionalPauseDoesNotRaiseStaleAlarm() {
        agent.issueToken(11L, "admin");
        jdbc.update("UPDATE tb_agent SET last_report_dt = DATEADD('MINUTE', -20, CURRENT_TIMESTAMP) WHERE server_id = 11");
        agent.controlDaemon(11L, "stop", "admin");
        alarms.checkAll();
        assertTrue(alarms.getActive().stream().noneMatch(a -> a.getServerId() == 11 && "AGENT_STALE".equals(a.getAlarmType())),
                "웹에서 중지한 경우 보고 끊김 알람 없음");

        agent.controlDaemon(11L, "start", "admin");
        alarms.checkAll();
        AlarmView a = alarms.getActive().stream()
                .filter(x -> x.getServerId() == 11 && "AGENT_STALE".equals(x.getAlarmType())).findFirst().orElse(null);
        assertNotNull(a, "수집 중인데 20분 보고 없음 → 알람");

        agent.changeCollectInterval(11L, 600, "admin");
        alarms.checkAll();
        assertTrue(alarms.getActive().stream().noneMatch(x -> x.getServerId() == 11 && "AGENT_STALE".equals(x.getAlarmType())),
                "수집 주기 10분이면 기준 30분 → 20분은 정상");
    }
}
