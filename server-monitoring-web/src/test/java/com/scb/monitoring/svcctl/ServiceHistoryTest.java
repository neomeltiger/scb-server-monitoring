package com.scb.monitoring.svcctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.ReadOnlyWriteGuard;
import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.svcctl.dto.ImportForm;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.dto.HistoryView;
import com.scb.monitoring.topology.service.TopologyService;

/** 서비스(service.list) CPU / MEM 이력 - 메인 홈에서 골라서 그래프로 보기 (seed: server 11 = 서비스 27건) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AgentService.class, ServiceCtlService.class, TopologyService.class, AlarmService.class, ServerLogService.class,
        ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class, com.scb.monitoring.agent.service.HeartbeatService.class})
@EnableConfigurationProperties(AgentProperties.class)
class ServiceHistoryTest {

    @Autowired AgentService agent;
    @Autowired ServiceCtlService svc;
    @Autowired TopologyService topology;
    @Autowired JdbcTemplate jdbc;

    private int histCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_service_metric_hist", Integer.class);
    }

    @Test
    void newClientFormatStoresHistory() {
        agent.ingestReport(11L, "[service]\nSCB_fs_app1|RUNNING|6012|3.4|1.2\nSCB_xml_app1|STOPPED|-|0|0\n"
                + "SCB_hub_app1|RUNNING|6048||\nNOT_REGISTERED|RUNNING|1|5.0|1.0\n", "127.0.0.1");
        assertEquals(2, histCount(), "실행 중(CPU 있음) + 중지 / CPU 없는 실행 중·미등록 서비스는 저장 안 함");
        agent.ingestReport(11L, "[service]\nSCB_fs_app1|RUNNING|6012|7.5|1.3\n", "127.0.0.1");

        HistoryView h = topology.getHistory(30).get(11L);
        assertNotNull(h);
        HistoryView.ProcessSeries fs = h.getServices().get("SCB_fs_app1");
        assertEquals(2, fs.getCpu().size());
        assertEquals(3.4, fs.getCpu().get(0), 0.001);
        assertEquals(7.5, fs.getCpu().get(1), 0.001, "시간순");
        assertEquals(0.0, h.getServices().get("SCB_xml_app1").getCpu().get(0), 0.001);
        assertNull(h.getServices().get("SCB_hub_app1"));
        assertEquals("RUNNING", svc.getServerServices(11L).getServices().get(0).getState(), "상태 반영은 그대로");
    }

    @Test
    void oldClientFormatStoresNoHistory() {
        agent.ingestReport(11L, "[service]\nSCB_fs_app1|RUNNING|6012\nSCB_xml_app1|STOPPED|\n", "127.0.0.1");
        assertEquals(0, histCount(), "1.2 이하 형식(CPU 칸 없음)은 이력 없음");
    }

    @Test
    void historyIsRemovedWithServiceOrServer() {
        agent.ingestReport(11L, "[service]\nSCB_fs_app1|RUNNING|6012|3.4|1.2\nSCB_xml_app1|RUNNING|6031|1.0|1.0\n", "127.0.0.1");
        assertEquals(2, histCount());
        // service.list 를 다시 가져오면서 SCB_xml_app1 제외 → 그 이력도 삭제
        ImportForm f = new ImportForm();
        f.setScriptHome("/shared/opt/pos");
        f.setText("2|2|SCB_fs_app1|murex|SCB_fs_app1/fs.sh||start|stop|check\n");
        svc.importServiceList(11L, f);
        assertEquals(1, histCount());
        topology.deleteServer(11L);
        assertEquals(0, histCount());
        assertTrue(topology.getHistory(30).get(11L) == null);
    }
}
