package com.scb.monitoring.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.scb.monitoring.agent.dto.AgentStatusView;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.dto.AlarmView;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/** 서버 장애 알람 / 에이전트 등록 현황 (seed: server 23 = Down, 에이전트 없음) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({AlarmService.class, AgentService.class, ServiceCtlService.class, TopologyService.class,
        com.scb.monitoring.serverlog.service.ServerLogService.class, com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class})
@EnableConfigurationProperties(AgentProperties.class)
class AlarmServiceTest {

    @Autowired AlarmService alarms;
    @Autowired AgentService agents;
    @Autowired JdbcTemplate jdbc;

    private AlarmView only(List<AlarmView> list, long serverId, String type) {
        List<AlarmView> m = new java.util.ArrayList<>();
        for (AlarmView a : list) if (a.getServerId() == serverId && a.getAlarmType().equals(type)) m.add(a);
        assertEquals(1, m.size(), serverId + " " + type);
        return m.get(0);
    }

    @Test
    void serverDownOpensOnceAndClosesWhenUp() {
        assertEquals(1, alarms.checkAll(), "Down 서버 1대");
        assertEquals(0, alarms.checkAll(), "같은 알람은 다시 열지 않음");
        AlarmView a = only(alarms.getActive(), 23, "SERVER_DOWN");
        assertEquals("CRITICAL", a.getAlarmLevel());
        assertEquals("OPEN", a.getStateCd());

        jdbc.update("UPDATE tb_server SET status_cd = 'Active' WHERE server_id = 23");
        alarms.checkServer(23L);
        assertTrue(alarms.getActive().isEmpty());
        assertEquals(1, alarms.getRecentClosed().size());
    }

    @Test
    void acknowledgeRecordsActionMemo() {
        alarms.checkAll();
        AlarmView a = only(alarms.getActive(), 23, "SERVER_DOWN");
        assertThrows(TopologyException.class, () -> alarms.acknowledge(a.getAlarmId(), "admin", "  "));
        AlarmView acked = alarms.acknowledge(a.getAlarmId(), "admin", "전원 재기동 요청");
        assertEquals("ACK", acked.getStateCd());
        assertEquals("admin", acked.getAckUser());
        assertEquals("전원 재기동 요청", acked.getActionMemo());
        assertEquals(0, alarms.checkAll(), "조치 중(ACK) 알람이 있으면 새로 열지 않음");
    }

    @Test
    void agentStaleOpensWarning() {
        // server 11 : 10분 전 마지막 보고 (기본 stale 300초)
        jdbc.update("INSERT INTO tb_agent (server_id, token_hash, issue_dt, issue_user, last_report_dt) " +
                "VALUES (11, 'h11', CURRENT_TIMESTAMP, 'admin', DATEADD('MINUTE', -10, CURRENT_TIMESTAMP))");
        // server 12 : 발급만 하고 보고 없음 → 알람 아님 (미등록 목록에만 표시)
        jdbc.update("INSERT INTO tb_agent (server_id, token_hash, issue_dt, issue_user) VALUES (12, 'h12', CURRENT_TIMESTAMP, 'admin')");
        alarms.checkAll();
        AlarmView a = only(alarms.getActive(), 11, "AGENT_STALE");
        assertEquals("WARNING", a.getAlarmLevel());
        assertEquals(2, alarms.getActive().size(), "SERVER_DOWN(23) + AGENT_STALE(11)");

        List<AgentStatusView> reg = agents.getAgentStatusList();
        assertEquals(10, reg.size());
        assertEquals(0, reg.stream().filter(AgentStatusView::isRegistered).count());
        assertEquals("STALE", reg.stream().filter(v -> v.getServerId() == 11).findFirst().get().getAgent().getState());
        assertEquals("WAITING", reg.stream().filter(v -> v.getServerId() == 12).findFirst().get().getAgent().getState());

        // 다시 보고하면 해소
        jdbc.update("UPDATE tb_agent SET last_report_dt = CURRENT_TIMESTAMP WHERE server_id = 11");
        alarms.checkServer(11L);
        assertEquals(1, alarms.getActive().size());
        assertTrue(agents.getAgentStatusList().stream().filter(v -> v.getServerId() == 11).findFirst().get().isRegistered());
    }

    @Test
    void deleteServerRemovesAlarms(@Autowired TopologyService topology) {
        alarms.checkAll();
        topology.deleteServer(23L);
        assertTrue(alarms.getActive().isEmpty());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_alarm", Integer.class));
    }
}
