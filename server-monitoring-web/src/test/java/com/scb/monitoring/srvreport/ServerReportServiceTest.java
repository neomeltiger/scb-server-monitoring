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

        assertFalse(reports.ingest(11L, it.getId(), "OK", "#SCBRPT|1|other\n"), "이미 받은 리포트는 다시 받지 않음 (관리자 요청 1건에 1번)");
        assertEquals(body, reports.getReport(11L, it.getId()).getContent());
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

    /** 자동 요청 주기 : 관리자가 정한 주기마다 요청을 만듦, 진행 중이면 건너뜀, 0 = 끔 */
    @Test
    void scheduledRequests() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 123, "1.12");
        assertEquals(0, reports.getSchedule(11L).get("intervalHr"), "처음에는 사용 안 함");
        assertThrows(TopologyException.class, () -> reports.saveSchedule(11L, 5, null, "admin"), "정한 주기만");

        java.util.Map<String, Object> sc = reports.saveSchedule(11L, 6, Arrays.asList("res", "sec"), "admin");
        assertEquals(6, sc.get("intervalHr"));
        assertEquals("sec,res", sc.get("sections"));
        long next = ((java.sql.Timestamp) sc.get("nextDt")).getTime();
        assertTrue(Math.abs(next - System.currentTimeMillis() - 6 * 3_600_000L) < 120_000L, "첫 자동 요청은 한 주기 뒤");
        assertEquals(0, reports.runSchedules(), "아직 때가 아님");

        jdbc.update("UPDATE tb_report_policy SET next_dt = ? WHERE server_id = 11", new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
        assertEquals(1, reports.runSchedules());
        ServerReportView.Item it = reports.getView(11L).getReports().get(0);
        assertEquals("REQUESTED", it.getStatus());
        assertEquals("sec,res", it.getSections());
        assertEquals("자동(admin)", jdbc.queryForObject("SELECT req_user FROM tb_server_report WHERE report_id = ?", String.class, it.getId()));
        assertEquals(0, reports.runSchedules(), "다음 주기로 넘어감 - 두 번 만들지 않음");
        assertTrue(reports.getSchedule(11L).get("lastReqDt") != null);

        // 때가 됐는데 진행 중인 요청이 있으면 이번은 건너뛰고 다음 주기로
        jdbc.update("UPDATE tb_report_policy SET next_dt = ? WHERE server_id = 11", new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
        assertEquals(0, reports.runSchedules());
        assertEquals(1, reports.getView(11L).getReports().size());
        assertTrue(((java.sql.Timestamp) reports.getSchedule(11L).get("nextDt")).getTime() > System.currentTimeMillis());

        // 끄기
        reports.saveSchedule(11L, 0, null, "admin");
        assertEquals(null, reports.getSchedule(11L).get("nextDt"));
        jdbc.update("UPDATE tb_report_policy SET next_dt = ? WHERE server_id = 11", new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
        assertEquals(0, reports.runSchedules(), "꺼진 주기는 요청하지 않음");

        reports.deleteByServer(11L);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_report_policy", Integer.class), "서버 삭제 시 함께 삭제");
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
    void oldClientGetsWebBasicReport() {
        agent.issueToken(11L, "admin");
        agent.pullCommands(11L, "RUNNING", 1, "1.11");
        jdbc.update("UPDATE tb_server_metric SET disk_pct = 93.0 WHERE server_id = 11");
        jdbc.update("UPDATE tb_server_process SET running_yn = 'N' WHERE server_id = 11");
        jdbc.update("INSERT INTO tb_log_source (server_id, log_nm, log_path, tail_lines, use_yn, sort_ord) VALUES (11, 'report-test', '/var/log/report_test.log', 200, 'Y', 0)");
        Long logId = jdbc.queryForObject("SELECT log_id FROM tb_log_source WHERE server_id = 11 AND log_path = '/var/log/report_test.log'", Long.class);
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 12; i++) log.append("Oct 13 10:00:0").append(i % 10).append(" krcasmrx11 sshd[1]: Failed password for root from 10.1.2.3 port 22 ssh2\n");
        log.append("Oct 13 10:01:00 krcasmrx11 su: 'su root' failed for kim on /dev/pts/1\n");
        log.append("Oct 13 10:02:00 krcasmrx11 genunix: WARNING: /: file system full\n");
        jdbc.update("INSERT INTO tb_log_snapshot (log_id, server_id, status_cd, line_cnt, byte_cnt, content) VALUES (?, 11, 'OK', 14, 100, ?)", logId, log.toString());

        ServerReportView v = reports.request(11L, null, "admin");
        ServerReportView.Item it = v.getReports().get(0);
        assertEquals("DONE", it.getStatus(), "1.11 은 웹 기본 리포트를 바로 완료");
        assertEquals("sec,res,svc,chg,web", it.getSections());
        assertEquals("", rptLines(agent.pullCommands(11L, "RUNNING", 1, "1.11")), "에이전트에는 전달하지 않음");

        String c = reports.getReport(11L, it.getId()).getContent();
        assertTrue(c.startsWith("#SCBRPT|1|"), c);
        assertTrue(c.contains("#WARN|SSH 로그인 실패 12건"), c);
        assertTrue(c.contains("  12회  10.1.2.3"), c);
        assertTrue(c.contains("#WARN|su / sudo 실패 1건"), c);
        assertTrue(c.contains("#WARN|디스크 사용률 93% (위험 90% 이상)"), c);
        assertTrue(c.contains("#WARN|커널·하드웨어 오류 메시지 1건"), c);
        assertTrue(c.contains("#ITEM|scbproc|"), c);
        assertTrue(c.contains("#WARN|등록 프로세스 실행 안 됨"), c);
        assertTrue(c.contains("#END|0"), c);
        assertTrue(reports.getReport(11L, it.getId()).getWarnCnt() >= 5);

        // 분야 지정
        ServerReportView v2 = reports.request(11L, Collections.singletonList("res"), "admin");
        String c2 = reports.getReport(11L, v2.getReports().get(0).getId()).getContent();
        assertTrue(c2.contains("#SEC|res|") && !c2.contains("#SEC|sec|") && !c2.contains("#SEC|svc|"), c2);
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
