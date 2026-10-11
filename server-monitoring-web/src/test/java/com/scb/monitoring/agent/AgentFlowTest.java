package com.scb.monitoring.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.svcctl.dto.CommandForm;
import com.scb.monitoring.svcctl.dto.CommandView;
import com.scb.monitoring.svcctl.dto.ImportForm;
import com.scb.monitoring.svcctl.dto.ImportResult;
import com.scb.monitoring.svcctl.dto.ServerServicesView;
import com.scb.monitoring.svcctl.dto.ServiceView;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.dto.HistoryView;
import com.scb.monitoring.topology.dto.ServerView;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/**
 * 에이전트 연동 흐름 : 토큰 → 보고(자원/프로세스/서비스) → 제어 명령 요청·전달·결과 → service.list 가져오기 → 그래프 이력.
 * H2 메모리 DB 에서 같은 Mapper(ANSI SQL)와 초기 데이터를 사용합니다.
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql,classpath:db/users.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AgentService.class, ServiceCtlService.class, TopologyService.class, AlarmService.class,
        com.scb.monitoring.serverlog.service.ServerLogService.class, com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class})
@EnableConfigurationProperties(AgentProperties.class)
class AgentFlowTest {

    @Autowired AgentService agent;
    @Autowired ServiceCtlService svc;
    @Autowired TopologyService topology;

    private static CommandForm cmd(String target, String c) {
        CommandForm f = new CommandForm();
        f.setTarget(target);
        f.setCmd(c);
        return f;
    }

    @Test
    void tokenIssueAndAuthenticate() {
        assertEquals("NONE", agent.getAgent(11L).getState());
        String token = agent.issueToken(11L, "admin");
        assertEquals(64, token.length());
        assertEquals(Long.valueOf(11), agent.authenticate(token));
        assertNull(agent.authenticate("0000000000000000000000000000000000000000000000000000000000000000"));
        assertNull(agent.authenticate("not-a-token"));
        assertEquals("WAITING", agent.getAgent(11L).getState());

        String again = agent.issueToken(11L, "admin");       // 재발급하면 이전 토큰 무효
        assertNull(agent.authenticate(token));
        assertEquals(Long.valueOf(11), agent.authenticate(again));
    }

    @Test
    void configListsProcessesAndPeer() {
        String cfg = agent.buildConfig(11L);
        assertTrue(cfg.contains("interval=60"));
        assertTrue(cfg.contains("peer=10.0.13.218"));                         // 동기화 대상 AP #2
        assertTrue(cfg.contains("proc|1|mx_kernel_engine|/opt/murex/bin/mx_kernel"));
    }

    @Test
    void reportUpdatesMetricProcessServiceAndHistory() {
        agent.issueToken(11L, "admin");
        String report = "[sys]\nhostname=krcasmrx11\nos=SunOS 5.10\nagent_ver=1.0\n"
                + "[metric]\ncpu=33.3\nmem=71.5\ndisk=55\nsessions=1500\n"
                + "[proc]\n1|Y|4242|9.9|12.0\n4|N|-|0|0\n13|Y|1|1|1\nbad line\n"
                + "[service]\nSCB_fs_app1|RUNNING|5555\nSCB_xml_app1|STOPPED|\nevil;rm|RUNNING|1\n"
                + "[net]\npeer=10.0.13.218|UP|0.5\n";
        Map<String, Integer> applied = agent.ingestReport(11L, report, "127.0.0.1");
        assertEquals(1, applied.get("metric"));
        assertEquals(2, applied.get("proc"), "다른 서버의 프로세스(13)는 무시");
        assertEquals(2, applied.get("service"), "형식이 틀린 서비스명은 무시");

        ServerView s = topology.getServer(11L);
        assertEquals("33.3%", s.getCpu());
        assertEquals("1,500", s.getSessions());
        assertEquals("4242", s.getProcesses().get(0).getPid());
        assertEquals("9.9%", s.getProcesses().get(0).getCpu());

        ServerServicesView sv = svc.getServerServices(11L);
        ServiceView fs = sv.getServices().get(0);
        assertEquals("SCB_fs_app1", fs.getName());
        assertEquals("RUNNING", fs.getState());
        assertEquals("5555", fs.getPid());
        assertEquals("STOPPED", sv.getServices().get(1).getState());
        assertEquals("OK", sv.getAgent().getState());
        assertEquals("krcasmrx11", sv.getAgent().getAgentHost());

        HistoryView h = topology.getHistory(30).get(11L);
        assertEquals(30, h.getCpu().size(), "샘플 30건 중 최근 30건");
        assertEquals("AGENT", h.getSource(), "가장 최근 값은 에이전트 수집");
        assertEquals(33.3, h.getCpu().get(29), 0.001);
        assertEquals(9.9, h.getProcesses().get(1L).getCpu().get(29), 0.001);
    }

    @Test
    void commandRequestPullAndResult() {
        CommandView c = svc.requestCommand(11L, cmd("SCB_fs_app1", "restart"), "admin");
        assertEquals("REQUESTED", c.getState());
        svc.requestCommand(11L, cmd("group:7", "status"), "admin");

        String pulled = agent.pullCommands(11L);
        assertTrue(pulled.contains("cmd|" + c.getId() + "|restart|SCB_fs_app1"));
        assertTrue(pulled.contains("|status|group:7"));
        assertEquals("", agent.pullCommands(11L), "한 번 가져간 명령은 다시 주지 않음");

        assertTrue(agent.commandResult(11L, c.getId(), 0, "RESULT: OK=1 FAIL=0"));
        assertFalse(agent.commandResult(11L, c.getId(), 0, "again"), "완료된 명령에 다시 결과 보고 불가");
        assertFalse(agent.commandResult(12L, c.getId(), 0, "x"), "다른 서버 명령에 결과 보고 불가");

        ServerServicesView sv = svc.getServerServices(11L);
        CommandView done = sv.getCommands().stream().filter(x -> x.getId().equals(c.getId())).findFirst().get();
        assertEquals("DONE", done.getState());
        assertEquals(Integer.valueOf(0), done.getExitCode());
        assertEquals(1, sv.getActiveCommands(), "group:7 status 는 SENT 상태로 남아 있음");
    }

    @Test
    void commandValidation() {
        // Apache 는 start|stop|check 만 지원 → kill 불가
        assertThrows(TopologyException.class, () -> svc.requestCommand(11L, cmd("Apache", "kill"), "admin"));
        assertThrows(TopologyException.class, () -> svc.requestCommand(11L, cmd("NO_SUCH_SVC", "start"), "admin"));
        assertThrows(TopologyException.class, () -> svc.requestCommand(11L, cmd("group:99", "start"), "admin"));
        assertThrows(TopologyException.class, () -> svc.requestCommand(12L, cmd("all", "start"), "admin"),
                "서비스가 없는 서버");
        assertNotNull(svc.requestCommand(11L, cmd("Apache", "restart"), "admin"));
        assertNotNull(svc.requestCommand(11L, cmd("all", "status"), "admin"));
    }

    @Test
    void importServiceListUpsertsAndRejectsUnsafeLines() {
        ImportForm f = new ImportForm();
        f.setScriptHome("/opt/pos");
        f.setText("# GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT|ARG|CMDS\n"
                + "1|1|SVC_A|murex|SVC_A/a.sh||start|stop|test\n"
                + "2|2|SVC_B|murex|/abs/b.sh|x1|start|stop|check\n"
                + "3|3|SVC_C|murex|../../etc/passwd||start\n"            // 경로 이탈
                + "3|3|SVC_D|murex|d.sh||start;rm -rf /\n"               // 허용되지 않은 명령
                + "4|4|SVC_E|murex|e.sh\n");                              // 항목 부족
        ImportResult r = svc.importServiceList(12L, f);
        assertEquals(2, r.getAdded());
        assertEquals(3, r.getSkipped().size());

        ServerServicesView sv = svc.getServerServices(12L);
        assertEquals("/opt/pos/SVC_A/a.sh", sv.getServices().get(0).getScript());
        assertEquals("/abs/b.sh", sv.getServices().get(1).getScript());

        f.setText("1|5|SVC_A|murex|SVC_A/a.sh||start|stop\n");             // A 변경, B 삭제
        ImportResult r2 = svc.importServiceList(12L, f);
        assertEquals(1, r2.getUpdated());
        assertEquals(1, r2.getRemoved());
        assertEquals(Integer.valueOf(5), svc.getServerServices(12L).getServices().get(0).getLevel());
    }

    @Test
    void deleteServerRemovesAgentServicesHistory() {
        agent.issueToken(11L, "admin");
        svc.requestCommand(11L, cmd("all", "status"), "admin");
        topology.deleteServer(11L);
        assertNull(topology.getHistory(30).get(11L));
        assertThrows(TopologyException.class, () -> svc.getServerServices(11L));
    }
}
