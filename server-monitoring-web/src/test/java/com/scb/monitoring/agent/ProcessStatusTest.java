package com.scb.monitoring.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.agent.service.ProcessStatusService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.svcctl.dto.ServiceView;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.dto.ProcessView;
import com.scb.monitoring.topology.dto.ServerView;
import com.scb.monitoring.topology.service.TopologyService;

/**
 * 서버 정보를 받아오지 못하면 프로세스·서비스를 종료로 표시 (ProcessStatusService).
 *  - 서버 단위 : 에이전트 상태가 OK 아님 → 알람 점검 때 실행 중인 것 전부 종료
 *  - 프로세스 단위 : 보고에 빠진 프로세스·서비스만 종료
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql,classpath:db/users.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AgentService.class, ServiceCtlService.class, TopologyService.class, AlarmService.class, ProcessStatusService.class,
        com.scb.monitoring.serverlog.service.ServerLogService.class, com.scb.monitoring.ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AgentProperties.class)
class ProcessStatusTest {

    @Autowired AgentService agent;
    @Autowired AlarmService alarm;
    @Autowired TopologyService topology;
    @Autowired ServiceCtlService svc;

    private static final String HEAD = "[sys]\nhostname=krcasmrx11\nos=SunOS 5.10\nagent_ver=1.3\n"
            + "[metric]\ncpu=10\nmem=20\ndisk=30\nsessions=1\n";

    private ProcessView proc(Long procId) {
        return topology.getServer(11L).getProcesses().stream().filter(p -> p.getId().equals(procId)).findFirst().get();
    }

    private String svcState(String name) {
        return svc.getServerServices(11L).getServices().stream()
                .filter(s -> s.getName().equals(name)).map(ServiceView::getState).findFirst().get();
    }

    @Test
    void serverWithoutAgentStopsAllProcesses() {
        ServerView before = topology.getServer(11L);
        assertTrue(before.getProcesses().get(0).isRunning(), "초기 데이터 : 실행 중");

        alarm.checkAll();                                   // 에이전트 미설치(NONE) → 서버 정보 수신 안 됨

        for (ProcessView p : topology.getServer(11L).getProcesses()) {
            assertFalse(p.isRunning(), p.getName() + " 종료 표시");
        }
        assertEquals("-", proc(1L).getPid());
    }

    @Test
    void staleAfterReportStopsRunningServices() {
        agent.issueToken(11L, "admin");
        agent.ingestReport(11L, HEAD + "[proc]\n1|Y|100|1|1\n2|Y|200|1|1\n3|Y|300|1|1\n"
                + "[service]\nSCB_fs_app1|RUNNING|5555\nSCB_xml_app1|STOPPED|\n", "127.0.0.1");
        alarm.checkAll();                                   // 방금 보고 → OK : 그대로
        assertTrue(proc(1L).isRunning());
        assertEquals("RUNNING", svcState("SCB_fs_app1"));

        // 수집 중지(PAUSED) = 서버 정보 수신 안 됨
        agent.controlDaemon(11L, "stop", "admin");
        alarm.checkAll();
        assertFalse(proc(1L).isRunning());
        assertFalse(proc(3L).isRunning());
        assertEquals("STOPPED", svcState("SCB_fs_app1"));
    }

    @Test
    void processMissingFromReportIsStopped() {
        agent.issueToken(11L, "admin");
        agent.ingestReport(11L, HEAD + "[proc]\n1|Y|100|1|1\n2|Y|200|1|1\n3|Y|300|1|1\n"
                + "[service]\nSCB_fs_app1|RUNNING|5555\nSCB_xml_app1|RUNNING|6666\n", "127.0.0.1");
        assertTrue(proc(2L).isRunning());

        // 프로세스 2 와 서비스 SCB_xml_app1 정보가 빠진 보고
        Map<String, Integer> applied = agent.ingestReport(11L, HEAD + "[proc]\n1|Y|100|1|1\n3|Y|300|1|1\n"
                + "[service]\nSCB_fs_app1|RUNNING|5555\n", "127.0.0.1");
        assertEquals(2, applied.get("stopped"));
        assertFalse(proc(2L).isRunning());
        assertEquals("-", proc(2L).getPid());
        assertTrue(proc(1L).isRunning(), "보고된 프로세스는 그대로");
        assertEquals("STOPPED", svcState("SCB_xml_app1"));
        assertEquals("RUNNING", svcState("SCB_fs_app1"));

        // 이미 종료된 것은 다시 기록하지 않음, 서비스 상태를 보내지 않는 보고는 서비스 유지
        applied = agent.ingestReport(11L, HEAD + "[proc]\n1|Y|100|1|1\n3|Y|300|1|1\n", "127.0.0.1");
        assertEquals(0, applied.get("stopped"));
        assertEquals("RUNNING", svcState("SCB_fs_app1"));

        // 다시 보고되면 실행 중으로
        agent.ingestReport(11L, HEAD + "[proc]\n1|Y|100|1|1\n2|Y|222|1|1\n3|Y|300|1|1\n", "127.0.0.1");
        assertTrue(proc(2L).isRunning());
        assertEquals("222", proc(2L).getPid());
    }
}
