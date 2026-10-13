package com.scb.monitoring.srvreport.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.srvreport.domain.ServerReport;
import com.scb.monitoring.srvreport.dto.ServerReportView;
import com.scb.monitoring.srvreport.mapper.ServerReportMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 점검 리포트 (관리자 전용, 요청할 때만).
 * <pre>
 *  웹 관리자 화면 [리포트 요청] → tb_server_report REQUESTED
 *  에이전트 데몬이 웹을 확인할 때 (GET /agent-api/v1/commands, 10초마다)  rpt|리포트ID|분야  로 전달 → SENT
 *  svr_collect_client 1.12 의 bin/collect_report.sh 실행 → POST /agent-api/v1/reports/{리포트ID}?status=OK|ERROR → DONE / ERROR
 *  서버별 최근 KEEP 건 보관
 * </pre>
 * 분야 : sec (보안 및 접근 제어) / res (시스템 자원) / svc (서비스 및 스케줄러 - 웹 관리 프로세스 강조) / chg (패키지 및 환경 변화)
 */
@Service
@Transactional(readOnly = true)
public class ServerReportService {

    private static final Logger log = LoggerFactory.getLogger(ServerReportService.class);

    public static final int KEEP = 20;
    /** 저장할 리포트 최대 길이 (문자) - 클라이언트는 512KB 까지 보냄 */
    static final int MAX_CONTENT_CHARS = 600 * 1024;
    /** 분야 (표시 순서) */
    public static final List<String> SECTIONS = Arrays.asList("sec", "res", "svc", "chg");
    /** 이 버전부터 리포트 지원 */
    public static final int MIN_MAJOR = 1;
    public static final int MIN_MINOR = 12;

    private final ServerReportMapper mapper;
    private final TopologyMapper topologyMapper;
    private final AgentMapper agentMapper;
    private final AgentProperties agentProps;

    public ServerReportService(ServerReportMapper mapper, TopologyMapper topologyMapper, AgentMapper agentMapper, AgentProperties agentProps) {
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.agentMapper = agentMapper;
        this.agentProps = agentProps;
    }

    // ===================== 화면 (ADMIN) =====================

    public ServerReportView getView(Long serverId) {
        Server s = findServer(serverId);
        Agent a = agentMapper.selectAgent(serverId);
        AgentView av = AgentView.of(a, agentProps.getStaleAfterSec());
        List<ServerReportView.Item> items = new ArrayList<>();
        for (ServerReport r : mapper.selectReportList(serverId)) items.add(ServerReportView.Item.of(r));
        return ServerReportView.of(serverId, s.getSystemNm(), s.getIpAddr(), av, supports(av.getAgentVer()), items);
    }

    /**
     * [리포트 요청] - 진행 중인 요청이 있으면 거절.
     * 클라이언트가 1.12 미만이면 서버 직접 점검을 못 하므로 웹에 이미 수집된 자료로 웹 기본 리포트를 바로 만듦 (DONE).
     */
    @Transactional
    public ServerReportView request(Long serverId, List<String> sections, String user) {
        Server server = findServer(serverId);
        Agent agent = agentMapper.selectAgent(serverId);
        if (agent == null) {
            throw TopologyException.badRequest("이 서버에는 에이전트(svr_collect_client)가 등록되지 않았습니다. 에이전트 관리에서 토큰을 발급·설치하세요.");
        }
        String sec = normalizeSections(sections);
        if (!supports(agent.getAgentVer())) return basicReport(server, agent, sec, user);
        for (ServerReport r : mapper.selectReportList(serverId)) {
            if (r.isActive() && !ServerReportView.Item.of(r).isOverdue()) {
                throw TopologyException.conflict("이미 진행 중인 리포트 요청이 있습니다. (#" + r.getReportId() + ") 결과를 받은 뒤 다시 요청하세요.");
            }
        }
        ServerReport r = new ServerReport();
        r.setServerId(serverId);
        r.setSections(sec);
        r.setStatusCd(ServerReport.REQUESTED);
        r.setReqUser(user);
        mapper.insertReport(r);
        prune(serverId);
        log.info("SERVER REPORT REQUEST server={} id={} sections={} by {}", serverId, r.getReportId(), sec, user);
        return getView(serverId);
    }

    public ServerReportView.Detail getReport(Long serverId, Long reportId) {
        return ServerReportView.Detail.of(findReport(serverId, reportId));
    }

    @Transactional
    public void delete(Long serverId, Long reportId, String user) {
        findReport(serverId, reportId);
        mapper.deleteReport(reportId);
        log.info("SERVER REPORT DELETE server={} id={} by {}", serverId, reportId, user);
    }

    /** 웹 기본 리포트 (클라이언트 1.11 이하) - 요청과 동시에 완료 */
    private ServerReportView basicReport(Server server, Agent agent, String sec, String user) {
        Long serverId = server.getServerId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        BasicReportBuilder.Input in = new BasicReportBuilder.Input();
        String host = agent.getAgentHost();
        in.host = host == null || host.trim().isEmpty() || "unknown".equalsIgnoreCase(host.trim()) ? server.getSystemNm() : host;
        in.os = agent.getAgentOs() != null ? agent.getAgentOs() : server.getOsType();
        in.clientVer = agent.getAgentVer();
        in.sections = Arrays.asList(sec.split(","));
        in.now = now;
        in.metric = mapper.selectBasicMetric(serverId);
        in.peak = mapper.selectBasicPeak(serverId, new Timestamp(now.getTime() - 24L * 3600 * 1000));
        in.services = mapper.selectBasicServices(serverId);
        in.processes = mapper.selectBasicProcesses(serverId);
        in.logs = mapper.selectBasicLogs(serverId);
        Timestamp week = new Timestamp(now.getTime() - 7L * 24 * 3600 * 1000);
        in.alarms = mapper.selectBasicAlarms(serverId, week);
        in.cmds = mapper.selectBasicCmds(serverId, week);
        String text = BasicReportBuilder.build(in);

        ServerReport r = new ServerReport();
        r.setServerId(serverId);
        r.setSections(sec + "," + BasicReportBuilder.WEB_SECTION);
        r.setStatusCd(ServerReport.REQUESTED);
        r.setReqUser(user);
        mapper.insertReport(r);
        text = clip(text);
        mapper.updateResult(r.getReportId(), ServerReport.DONE, countWarn(text), text.getBytes(StandardCharsets.UTF_8).length, text);
        prune(serverId);
        log.info("SERVER REPORT (web basic, client {}) server={} id={} sections={} warn={} by {}",
                agent.getAgentVer(), serverId, r.getReportId(), sec, countWarn(text), user);
        return getView(serverId);
    }

    // ===================== 에이전트 =====================

    /**
     * 에이전트가 명령을 가져갈 때 함께 내려줄 리포트 요청 (rpt|ID|분야). 없으면 빈 문자열.
     * AgentService.pullCommands 의 쓰기 트랜잭션 안에서 호출 (REQUESTED → SENT).
     * 테스트 구성(@MybatisTest)을 단순하게 하려고 매퍼만 받는 static 메서드로 둠.
     */
    public static String pullRequests(ServerReportMapper mapper, Long serverId) {
        StringBuilder sb = new StringBuilder();
        for (ServerReport r : mapper.selectRequested(serverId)) {
            if (mapper.markSent(r.getReportId()) == 0) continue;
            sb.append("rpt|").append(r.getReportId()).append('|').append(r.getSections()).append('\n');
            log.info("SERVER REPORT → agent server={} id={} sections={}", serverId, r.getReportId(), r.getSections());
        }
        return sb.toString();
    }

    /** 에이전트가 보낸 리포트. 다른 서버의 리포트이거나 없으면 false */
    @Transactional
    public boolean ingest(Long serverId, Long reportId, String status, String content) {
        ServerReport r = mapper.selectReport(reportId);
        if (r == null || !serverId.equals(r.getServerId())) return false;
        String text = clip(content == null ? "" : content);
        String st = "OK".equalsIgnoreCase(status) ? ServerReport.DONE : ServerReport.ERROR;
        mapper.updateResult(reportId, st, countWarn(text), text.getBytes(StandardCharsets.UTF_8).length, text);
        log.info("SERVER REPORT ← agent server={} id={} status={} warn={}", serverId, reportId, st, countWarn(text));
        return true;
    }

    // ===================== 서버 삭제 =====================

    @Transactional
    public void deleteByServer(Long serverId) {
        mapper.deleteReportsByServer(serverId);
    }

    // ===================== 내부 =====================

    /** 분야 목록 → "sec,res,svc,chg" 중 고른 것 (표시 순서). 비었으면 전체 */
    static String normalizeSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) return String.join(",", SECTIONS);
        Set<String> want = new LinkedHashSet<>();
        for (String s : sections) {
            String v = s == null ? "" : s.trim().toLowerCase();
            if (v.isEmpty()) continue;
            if ("all".equals(v)) return String.join(",", SECTIONS);
            if (!SECTIONS.contains(v)) throw TopologyException.badRequest("알 수 없는 분야입니다: " + s + " (sec, res, svc, chg)");
            want.add(v);
        }
        if (want.isEmpty()) return String.join(",", SECTIONS);
        List<String> out = new ArrayList<>();
        for (String s : SECTIONS) if (want.contains(s)) out.add(s);
        return String.join(",", out);
    }

    /** 저장 한도를 넘으면 자르고 경고 한 줄 */
    static String clip(String text) {
        if (text.length() <= MAX_CONTENT_CHARS) return text;
        return text.substring(0, MAX_CONTENT_CHARS) + "\n#WARN|리포트가 너무 커서 뒷부분을 잘랐습니다.\n";
    }

    static int countWarn(String text) {
        int n = 0;
        int i = 0;
        while (i < text.length()) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = text.length();
            if (text.startsWith("#WARN|", i)) n++;
            i = e + 1;
        }
        return n;
    }

    /** 클라이언트 1.12 이상인지 (모르면 true - 버전을 아직 보고하지 않은 경우) */
    static boolean supports(String ver) {
        if (ver == null || ver.trim().isEmpty()) return true;
        try {
            String[] p = ver.trim().split("\\.");
            int major = Integer.parseInt(p[0].replaceAll("[^0-9].*$", ""));
            int minor = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9].*$", "")) : 0;
            return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void prune(Long serverId) {
        List<Long> old = mapper.selectOldReportIds(serverId, KEEP);
        if (!old.isEmpty()) mapper.deleteReports(old);
    }

    private Server findServer(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        if (s == null) throw TopologyException.notFound("서버를 찾을 수 없습니다. (id=" + serverId + ")");
        return s;
    }

    private ServerReport findReport(Long serverId, Long reportId) {
        ServerReport r = mapper.selectReport(reportId);
        if (r == null || !serverId.equals(r.getServerId())) throw TopologyException.notFound("리포트를 찾을 수 없습니다. (id=" + reportId + ")");
        return r;
    }
}
