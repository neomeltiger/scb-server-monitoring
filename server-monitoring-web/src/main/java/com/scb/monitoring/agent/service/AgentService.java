package com.scb.monitoring.agent.service;

import java.util.HashMap;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.dto.AgentStatusView;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.svcctl.domain.ServiceCommand;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.domain.MetricHist;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerMetric;
import com.scb.monitoring.topology.domain.ServerProcess;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 에이전트(krcasmrx11_client) 연동.
 * <ul>
 *   <li>토큰 발급 : 서버별 1개, 원문은 발급 시 한 번만 보여주고 DB 에는 SHA-256 만 저장</li>
 *   <li>보고 처리 : 자원 현황/이력, 프로세스 상태/이력, 서비스 상태, 동기화 대상 통신 상태</li>
 *   <li>설정 제공 : 보고 주기, 감시할 프로세스 목록, 동기화 대상 IP</li>
 *   <li>제어 명령 전달 / 결과 반영 (ServiceCtlService)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentMapper mapper;
    private final TopologyMapper topologyMapper;
    private final ServiceCtlService serviceCtl;
    private final AlarmService alarmService;
    private final ServerLogService serverLogService;
    private final com.scb.monitoring.svcctl.mapper.ServiceCtlMapper serviceCtlMapper;
    private final AgentProperties props;
    private final ProcessStatusService processStatus;
    private final HeartbeatService heartbeat;
    private final FallbackService fallback;

    public AgentService(AgentMapper mapper, TopologyMapper topologyMapper, ServiceCtlService serviceCtl, AgentProperties props,
                        AlarmService alarmService, ServerLogService serverLogService,
                        com.scb.monitoring.svcctl.mapper.ServiceCtlMapper serviceCtlMapper,
                        ProcessStatusService processStatus, HeartbeatService heartbeat, FallbackService fallback) {
        this.fallback = fallback;
        this.processStatus = processStatus;
        this.heartbeat = heartbeat;
        this.serviceCtlMapper = serviceCtlMapper;
        this.alarmService = alarmService;
        this.serverLogService = serverLogService;
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.serviceCtl = serviceCtl;
        this.props = props;
    }

    // ===================== 토큰 =====================

    /** 전체 서버의 에이전트 등록 현황 (미등록 / 보고 없음 / 끊김 먼저) */
    public List<AgentStatusView> getAgentStatusList() {
        Map<Long, Agent> agents = new HashMap<>();
        for (Agent a : mapper.selectAgentList()) agents.put(a.getServerId(), a);
        List<AgentStatusView> list = new ArrayList<>();
        for (Server s : topologyMapper.selectServerList()) {
            list.add(AgentStatusView.of(s, agents.get(s.getServerId()), props.getStaleAfterSec()));
        }
        return list;
    }

    public AgentView getAgent(Long serverId) {
        findServer(serverId);
        return AgentView.of(mapper.selectAgent(serverId), props.getStaleAfterSec());
    }

    /** 토큰 (재)발급. 반환값(원문)은 다시 조회할 수 없습니다. 재발급하면 이전 토큰은 즉시 무효. */
    @Transactional
    public String issueToken(Long serverId, String user) {
        findServer(serverId);
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = hex(b);
        String hash = sha256(token);
        if (mapper.selectAgent(serverId) == null) {
            mapper.insertAgent(serverId, hash, user);
        } else {
            mapper.updateAgentToken(serverId, hash, user);
        }
        log.info("AGENT TOKEN issued server={} by {}", serverId, user);
        return token;
    }

    /**
     * 토큰 제거 (회수) : tb_agent 행 삭제 - 그 서버의 클라이언트는 더 이상 인증되지 않음 (AGENT AUTH FAIL).
     * 수집 이력은 그대로. 발급된 토큰이 없으면 false.
     */
    @Transactional
    public boolean revokeToken(Long serverId, String user) {
        findServer(serverId);
        if (mapper.selectAgent(serverId) == null) return false;
        mapper.deleteAgent(serverId);
        log.info("AGENT TOKEN revoked server={} by {}", serverId, user);
        return true;
    }

    /** 토큰 → 서버 ID (없거나 틀리면 null) */
    public Long authenticate(String token) {
        if (token == null || !token.matches("^[0-9a-f]{64}$")) return null;
        return mapper.selectServerIdByToken(sha256(token));
    }

    // ===================== 설정 =====================

    /** 에이전트 설정 (text/plain) */
    public String buildConfig(Long serverId) {
        Server s = findServer(serverId);
        StringBuilder sb = new StringBuilder();
        sb.append("# scb agent config server=").append(serverId).append('\n');
        sb.append("server_id=").append(serverId).append('\n');
        sb.append("system_name=").append(s.getSystemNm()).append('\n');
        Agent ag = mapper.selectAgent(serverId);
        sb.append("interval=").append(ag != null ? ag.getCollectIntervalSec() : props.getReportIntervalSec()).append('\n');
        sb.append("collect=").append(ag == null || ag.isCollectOn() ? "on" : "off").append('\n');
        if (s.getPairServerId() != null) {
            Server pair = topologyMapper.selectServer(s.getPairServerId());
            if (pair != null) sb.append("peer=").append(pair.getIpAddr()).append('\n');
        }
        for (ServerProcess p : topologyMapper.selectProcessListByServer(serverId)) {
            sb.append("proc|").append(p.getProcId()).append('|').append(p.getProcNm()).append('|').append(p.getExecPath()).append('\n');
        }
        return sb.toString();
    }

    // ===================== 보고 =====================

    @Transactional
    public Map<String, Integer> ingestReport(Long serverId, String text, String clientIp) {
        Server server = findServer(serverId);
        AgentReport r = AgentReport.parse(text);
        Map<String, Integer> applied = new LinkedHashMap<>();

        // 에이전트 정보
        Agent a = new Agent();
        a.setServerId(serverId);
        a.setLastReportIp(clientIp);
        a.setAgentHost(r.sys.get("hostname"));
        a.setAgentOs(r.sys.get("os"));
        a.setAgentVer(AgentReport.clip(String.valueOf(r.sys.getOrDefault("agent_ver", "")), 20));
        a.setPeerStatus(r.peer);
        mapper.updateAgentReport(a);

        // 자원 현황 + 이력
        BigDecimal cpu = pctOrNull(r.metric.get("cpu"));
        BigDecimal mem = pctOrNull(r.metric.get("mem"));
        if (cpu != null && mem != null) {
            ServerMetric m = new ServerMetric();
            m.setServerId(serverId);
            m.setCpuPct(cpu);
            m.setMemPct(mem);
            m.setDiskPct(pctOrNull(r.metric.get("disk")));
            m.setSessionCnt(AgentReport.toCount(r.metric.get("sessions")));
            if (topologyMapper.updateMetric(m) == 0) topologyMapper.insertMetric(m);

            MetricHist h = new MetricHist();
            h.setServerId(serverId);
            h.setCpuPct(cpu);
            h.setMemPct(mem);
            h.setDiskPct(m.getDiskPct());
            h.setSessionCnt(m.getSessionCnt());
            h.setSrcCd(MetricHist.SRC_AGENT);
            topologyMapper.insertMetricHist(h);
            applied.put("metric", 1);
        }

        // 프로세스 상태 + 이력 (이 서버의 프로세스만)
        Set<Long> own = new HashSet<>();
        for (ServerProcess p : topologyMapper.selectProcessListByServer(serverId)) own.add(p.getProcId());
        Set<Long> reported = new HashSet<>();
        int procCount = 0;
        for (AgentReport.ProcState ps : r.procs) {
            if (!own.contains(ps.procId)) continue;
            reported.add(ps.procId);
            ServerProcess p = new ServerProcess();
            p.setServerId(serverId);
            p.setProcId(ps.procId);
            p.setPid(ps.pid);
            p.setCpuPct(ps.cpu);
            p.setMemPct(ps.mem);
            p.setRunning(ps.running);
            topologyMapper.updateProcessRuntime(p);

            MetricHist h = new MetricHist();
            h.setServerId(serverId);
            h.setProcId(ps.procId);
            h.setCpuPct(ps.cpu);
            h.setMemPct(ps.mem);
            h.setRunningYn(ps.running ? "Y" : "N");
            h.setSrcCd(MetricHist.SRC_AGENT);
            topologyMapper.insertProcessHist(h);
            procCount++;
        }
        applied.put("proc", procCount);

        // 서비스 상태 (service.list)
        serviceCtl.updateStates(serverId, r.services);
        applied.put("service", r.services.size());

        // 보고에 빠진 프로세스·서비스 → 종료 + 기록 (서비스 상태를 보내지 않는 이전 클라이언트는 서비스 제외)
        Set<String> svcNames = new HashSet<>();
        for (String[] st : r.services) svcNames.add(st[0]);
        applied.put("stopped", processStatus.stopMissing(serverId, server.getSystemNm(), reported,
                r.services.isEmpty() ? null : svcNames));

        // 데몬이 보고함 = 데몬 실행 중 → 데몬 상태 반영 + 데몬 중단 중 대신 수집한 정보 삭제
        heartbeat.daemonReported(serverId);
        fallback.clear(serverId, "데몬 보고 수신");

        // 보고가 들어왔으니 "보고 끊김" 알람은 커밋 후 바로 해소
        alarmService.checkAfterCommit(serverId);

        // 보관 기간 지난 이력 정리
        Timestamp before = new Timestamp(System.currentTimeMillis() - props.getHistoryKeepDays() * 86400000L);
        topologyMapper.deleteMetricHistBefore(serverId, before);
        topologyMapper.deleteProcessHistBefore(serverId, before);
        serviceCtlMapper.deleteServiceHistBefore(serverId, before);
        heartbeat.cleanup(serverId, before);
        return applied;
    }

    // ===================== 제어 명령 =====================

    /**
     * 에이전트가 실행할 명령 (text/plain) - REQUESTED → SENT 변경이 있으므로 쓰기 트랜잭션
     *   cmd|ID|명령|대상      서비스 제어
     *   log|ID|줄수|경로      서버 로그 요청 (ServerLogService)
     */
    @Transactional
    public String pullCommands(Long serverId) {
        return pullCommands(serverId, null, null, null);
    }

    /**
     * 데몬 확인 (heartbeat) + 명령. 데몬은 첫 줄 ctl 의 값을 매번 그대로 적용합니다. (요청이 유실돼도 다음 확인 때 반영)
     *   ctl|collect=on|interval=60|shutdown=N|report=N   (report=Y : 서버 복구 직후 - 바로 수집·보고)
     * 종료 요청이면 shutdown=Y 만 내려주고(명령·로그 요청은 다음 데몬이 받도록 남겨 둠) 요청을 해제합니다.
     * 수집 중지 중에는 로그 요청을 보내지 않습니다. (서비스 제어 명령은 계속 처리)
     * @param daemonState 데몬이 보낸 상태 (RUNNING / PAUSED), 이전 cron 클라이언트는 null
     */
    @Transactional
    public String pullCommands(Long serverId, String daemonState, Integer pid, String ver) {
        Agent ag = mapper.selectAgent(serverId);
        if (daemonState != null && (Agent.D_RUNNING.equals(daemonState) || Agent.D_PAUSED.equals(daemonState))) {
            mapper.updateHeartbeat(serverId, daemonState, pid, ver == null ? null : AgentReport.clip(ver, 20));
            heartbeat.legacyBeat(ag, pid, ver);      // 하트비트 셸 없는 이전 데몬 : 웹 확인 = 생존 신호
        } else {
            mapper.updatePollOnly(serverId);
        }
        StringBuilder sb = new StringBuilder();
        if (ag != null) {
            boolean shutdown = ag.isShutdownRequested() && daemonState != null;
            sb.append("ctl|collect=").append(ag.isCollectOn() ? "on" : "off")
              .append("|interval=").append(ag.getCollectIntervalSec())
              .append("|shutdown=").append(shutdown ? "Y" : "N")
              .append("|report=").append(daemonState != null && heartbeat.consumeReportNow(serverId) ? "Y" : "N").append('\n');
            if (shutdown) {
                mapper.markShutdownSent(serverId);
                log.info("AGENT DAEMON shutdown → server={} pid={}", serverId, pid);
                return sb.toString();
            }
        }
        List<ServiceCommand> list = serviceCtl.pullCommands(serverId);
        for (ServiceCommand c : list) {
            sb.append("cmd|").append(c.getCmdId()).append('|').append(c.getCmdCd()).append('|').append(c.getTarget()).append('\n');
        }
        // 서버 로그 주기 요청 (요청 시각이 되었으면 log|ID|줄수|경로) - 수집 중지 중에는 보내지 않음
        if (ag == null || ag.isCollectOn()) sb.append(serverLogService.pullLogRequests(serverId));
        return sb.toString();
    }

    /** 데몬이 종료하면서 보냄 (POST /agent-api/v1/daemon?state=STOPPED) */
    @Transactional
    public void daemonStopped(Long serverId) {
        mapper.updateDaemonStopped(serverId);
        log.info("AGENT DAEMON stopped server={}", serverId);
        // 감시 프로세스·서비스 즉시 종료 표시 + 하트비트 STOP 이벤트
        heartbeat.daemonStopped(serverId);
        alarmService.checkAfterCommit(serverId);
    }

    // ===================== 데몬 제어 (웹, ADMIN) =====================

    /**
     * start    : 자료 수집 시작 (중지했던 수집 재개, 종료 요청 취소)
     * stop     : 자료 수집 중지 (데몬은 살아서 웹 확인만 계속 - 다시 시작 가능)
     * shutdown : 데몬 프로세스 종료 (이후에는 서버에서 bin/scb_client.sh start 로 시작)
     */
    @Transactional
    public AgentView controlDaemon(Long serverId, String action, String user) {
        Agent a = requireAgent(serverId);
        if ("start".equals(action)) {
            mapper.updateCollect(serverId, "Y", "N", user);
        } else if ("stop".equals(action)) {
            mapper.updateCollect(serverId, "N", "N", user);
        } else if ("shutdown".equals(action)) {
            mapper.updateCollect(serverId, a.getCollectYn(), "Y", user);
        } else {
            throw TopologyException.badRequest("알 수 없는 동작입니다: " + action);
        }
        log.info("AGENT DAEMON {} server={} by {}", action, serverId, user);
        return getAgent(serverId);
    }

    @Transactional
    public AgentView changeCollectInterval(Long serverId, int sec, String user) {
        requireAgent(serverId);
        if (!Agent.isAllowedInterval(sec)) {
            throw TopologyException.badRequest("수집 주기는 10초 / 30초 / 1분 / 2분 / 5분 / 10분 중 하나입니다.");
        }
        mapper.updateCollectInterval(serverId, sec, user);
        log.info("AGENT COLLECT INTERVAL server={} {}s by {}", serverId, sec, user);
        return getAgent(serverId);
    }

    private Agent requireAgent(Long serverId) {
        findServer(serverId);
        Agent a = mapper.selectAgent(serverId);
        if (a == null) throw TopologyException.badRequest("에이전트 토큰이 발급되지 않은 서버입니다. 네트워크 구성도에서 먼저 토큰을 발급하세요.");
        return a;
    }

    @Transactional
    public boolean commandResult(Long serverId, Long cmdId, int exitCode, String output) {
        return serviceCtl.completeCommand(serverId, cmdId, exitCode, output);
    }

    // ===================== 내부 =====================

    private Server findServer(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        if (s == null) throw TopologyException.notFound("서버를 찾을 수 없습니다. (id=" + serverId + ")");
        return s;
    }

    private static BigDecimal pctOrNull(String v) {
        return v == null ? null : AgentReport.pct(v);
    }

    static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
