package com.scb.monitoring.srvreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.srvreport.dto.ServerReportView;
import com.scb.monitoring.srvreport.service.ServerReportService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/** 서버 점검 리포트 : 요청 → 에이전트 전달(rpt|) → 수신 → 목록·내용·삭제 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({ServerReportService.class, ServerLogService.class, AgentService.class, ServiceCtlService.class, AlarmService.class, TopologyService.class,
        com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class,
        com.scb.monitoring.agent.service.HeartbeatService.class, com.scb.monitoring.agent.service.FallbackService.class})
@EnableConfigurationProperties(AgentProperties.class)
class ServerReportServiceTest {

    @Autowired ServerReportService reports;
    @Autowired AgentService agent;
    @Autowired TopologyService topology;
    @Autowired JdbcTemplate jdbc;

    private static String rptLines(String commands) {
        StringBuilder sb = new StringBuilder();
        for (String l : commands.split("\n")) if (l.startsWith("rpt|")) sb.append(l).append('\n');
        return sb.toString();
    }

    @Test
    void requestPullIngestFlow() {
        assertThrows(TopologyException.class, () -> reports.request(11L, null, "admin"), "에이전트 없는 서버는 요청 불가");
        agent.issueToken(11L, "admin");

        assertEquals("", rptLines(agent.pullCommands(11L)), "요청 전에는 없음");

        ServerReportView v = reports.request(11L, Arrays.asList("chg", "sec"), "admin");
        assertEquals(1, v.getReports().size());
        ServerReportView.Item it = v.getReports().get(0);
        assertEquals("REQUESTED", it.getStatus());
        assertEquals("sec,chg", it.getSections(), "표시 순서로 정리");

        assertThrows(TopologyException.class, () -> reports.request(11L, null, "admin"), "진행 중이면 다시 요청 불가");

        // 수집 중지 중에도 리포트 요청은 전달
        jdbc.update("UPDATE tb_agent SET collect_yn = 'N' WHERE server_id = 11");
        assertEquals("rpt|" + it.getId() + "|sec,chg\n", rptLines(agent.pullCommands(11L, "PAUSED", 123, "1.12")));
        assertEquals("", rptLines(agent.pullCommands(11L, "PAUSED", 123, "1.12")), "한 번만 전달");
        assertEquals("SENT", reports.getView(11L).getReports().get(0).getStatus());

        String body = "#SCBRPT|1|krcasmrx11|SunOS|2026-10-13 10:00:00|sec,chg\n#SEC|sec|보안\n#ITEM|ssh|SSH|/var/adm/authlog\n"
                + "#SUM|로그인 실패 12건\n#WARN|SSH 로그인 실패 12건\nOct 13 sshd: Failed password\n#WARN|su 실패 1건\n#END|3\n";
        assertTrue(reports.ingest(11L, it.getId(), "OK", body));
        ServerReportView.Detail d = reports.getReport(11L, it.getId());
        assertEquals("DONE", d.getStatus());
        assertEquals(2, d.getWarnCnt());
        assertEquals(body, d.getContent());
        assertTrue(d.getByteCnt() > body.length(), "UTF-8 바이트 수");

        assertFalse(reports.ingest(12L, it.getId(), "OK", body), "다른 서버의 리포트는 무시");
        assertFalse(reports.ingest(11L, 999999L, "OK", body));

        // 완료 후에는 다시 요청 가능, 전체 분야
        ServerReportView v2 = reports.request(11L, Collections.singletonList("all"), "admin");
        assertEquals("sec,res,svc,chg", v2.getReports().get(0).getSections());
        Long id2 = v2.getReports().get(0).getId();
        agent.pullCommands(11L, "RUNNING", 123, "1.12");
        assertTrue(reports.ingest(11L, id2, "ERROR", "boom"));
        assertEquals("ERROR", reports.getReport(11L, id2).getStatus());

        reports.delete(11L, id2, "admin");
        assertEquals(1, reports.getView(11L).getReports().size());
        assertThrows(TopologyException.class, () -> reports.getReport(11L, id2));
    }

    @Test
    void rejectsUnknownSectionAndKeepsLatest20() {
        agent.issueToken(11L, "admin");
        assertThrows(TopologyException.class, () -> reports.request(11L, Collections.singletonList("rm -rf"), "admin"));
        for (int i = 0; i < 22; i++) {
            ServerReportView v = reports.request(11L, null, "admin");
            Long id = v.getReports().get(0).getId();
            agent.pullCommands(11L, "RUNNING", 1, "1.12");
            reports.ingest(11L, id, "OK", "#END|1\n");
        }
        assertEquals(ServerReportService.KEEP, reports.getView(11L).getReports().size(), "서버별 최근 20건");
    }

    @Test
    void serverDeleteRemovesReports() {
        agent.issueToken(12L, "admin");
        reports.request(12L, null, "admin");
        topology.deleteServer(12L);
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_server_report WHERE server_id = 12", Integer.class));
    }

    @Test
    void oldClientIsFlagged() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 1, "1.11");
        assertFalse(reports.getView(11L).isClientSupported());
        agent.pullCommands(11L, "RUNNING", 1, "1.12");
        assertTrue(reports.getView(11L).isClientSupported());
    }
}
