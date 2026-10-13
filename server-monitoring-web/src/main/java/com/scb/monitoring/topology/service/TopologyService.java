package com.scb.monitoring.topology.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.alarm.mapper.AlarmMapper;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.mapper.ServerLogMapper;
import com.scb.monitoring.svcctl.mapper.ServiceCtlMapper;
import com.scb.monitoring.topology.domain.MetricHist;
import com.scb.monitoring.topology.domain.NetNode;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerMetric;
import com.scb.monitoring.topology.domain.ServerProcess;
import com.scb.monitoring.topology.dto.HistoryView;
import com.scb.monitoring.topology.dto.NodeView;
import com.scb.monitoring.topology.dto.ProcessForm;
import com.scb.monitoring.topology.dto.ProcessView;
import com.scb.monitoring.topology.dto.ServerForm;
import com.scb.monitoring.topology.dto.ServerView;
import com.scb.monitoring.topology.mapper.TopologyMapper;

/**
 * 네트워크 구성도 업무 로직 (서버 / 자원 현황 / 프로세스).
 * 화면(script.js)에서 처리하던 규칙을 서버로 옮겼습니다.
 *  - 상태에 따른 동기화 상태 문구
 *  - Down 이면 자원 0%·디스크 N/A·세션 0, Down 에서 복구되면 기본값
 *  - 신규 서버의 기본 프로세스 2건
 * 프로세스 기동/중지는 실제 서버를 제어하지 않는 화면용 시뮬레이션입니다. (PID/CPU/MEM 임의 값)
 */
@Service
@Transactional(readOnly = true)
public class TopologyService {

    private static final Logger log = LoggerFactory.getLogger(TopologyService.class);
    private static final String SEED_SCRIPT = "db/data.sql";

    private final TopologyMapper mapper;
    private final ServiceCtlMapper serviceCtlMapper;
    private final AgentMapper agentMapper;
    private final DataSource dataSource;
    private final AlarmMapper alarmMapper;
    private final ServerLogMapper serverLogMapper;
    private final com.scb.monitoring.srvreport.mapper.ServerReportMapper reportMapper;
    private final AlarmService alarmService;
    /** 에이전트 보고 끊김 기준 (app.agent.stale-after-sec) */
    private final int staleAfterSec;

    public TopologyService(TopologyMapper mapper, ServiceCtlMapper serviceCtlMapper, AgentMapper agentMapper, DataSource dataSource,
                           AlarmMapper alarmMapper, AlarmService alarmService, ServerLogMapper serverLogMapper,
                           com.scb.monitoring.srvreport.mapper.ServerReportMapper reportMapper,
                           @org.springframework.beans.factory.annotation.Value("${app.agent.stale-after-sec:300}") int staleAfterSec) {
        this.reportMapper = reportMapper;
        this.staleAfterSec = staleAfterSec;
        this.alarmMapper = alarmMapper;
        this.serverLogMapper = serverLogMapper;
        this.alarmService = alarmService;
        this.mapper = mapper;
        this.serviceCtlMapper = serviceCtlMapper;
        this.agentMapper = agentMapper;
        this.dataSource = dataSource;
    }

    // ===================== 조회 =====================

    public List<NodeView> getNodes() {
        List<NodeView> list = new ArrayList<>();
        for (NetNode n : mapper.selectNodeList()) list.add(NodeView.of(n));
        return list;
    }

    /** 서버 연결 스위치 (설치장소 선택 목록) */
    public List<NodeView> getSwitches() {
        List<NodeView> list = new ArrayList<>();
        for (NetNode n : mapper.selectNodeList()) {
            if (n.isSwitch()) list.add(NodeView.of(n));
        }
        return list;
    }

    public List<ServerView> getServers() {
        Map<Long, List<ServerProcess>> byServer = new LinkedHashMap<>();
        for (ServerProcess p : mapper.selectProcessList()) {
            byServer.computeIfAbsent(p.getServerId(), k -> new ArrayList<>()).add(p);
        }
        Map<Long, Agent> agents = new HashMap<>();
        for (Agent a : agentMapper.selectAgentList()) agents.put(a.getServerId(), a);
        List<ServerView> list = new ArrayList<>();
        for (Server s : mapper.selectServerList()) {
            list.add(ServerView.of(s, byServer.getOrDefault(s.getServerId(), Collections.emptyList()),
                    AgentView.of(agents.get(s.getServerId()), staleAfterSec)));
        }
        return list;
    }

    /** 서버 응답 : 서버 다운(하트비트 끊김 등)이면 화면 상태 Down, 데몬만 멈췄으면 daemonIssue (AgentView.serverDown) */
    public ServerView getServer(Long serverId) {
        return ServerView.of(findServer(serverId), mapper.selectProcessListByServer(serverId),
                AgentView.of(agentMapper.selectAgent(serverId), staleAfterSec));
    }

    // ===================== 서버 =====================

    @Transactional
    public ServerView createServer(ServerForm form) {
        checkSwitch(form.getSwitchNodeId());
        checkDuplicateIp(form.getIp(), null);

        Server s = new Server();
        apply(s, form);
        s.setSortOrd(mapper.selectMaxServerSortOrd() + 10);
        mapper.insertServer(s);

        mapper.insertMetric(s.isDown() ? ServerMetric.down(s.getServerId()) : ServerMetric.initial(s.getServerId()));

        // 기존 화면과 같은 기본 프로세스 2건
        addDefaultProcess(s, "app_main_daemon", "/opt/app/bin/daemon", 10);
        addDefaultProcess(s, "app_worker_node", "/opt/app/bin/worker", 20);

        alarmService.checkAfterCommit(s.getServerId());
        log.info("SERVER CREATE id={} name={} ip={}", s.getServerId(), s.getSystemNm(), s.getIpAddr());
        return getServer(s.getServerId());
    }

    @Transactional
    public ServerView updateServer(Long serverId, ServerForm form) {
        Server s = findServer(serverId);
        checkSwitch(form.getSwitchNodeId());
        checkDuplicateIp(form.getIp(), serverId);

        boolean wasDown = s.isDown();
        apply(s, form);
        mapper.updateServer(s);

        ServerMetric metric = null;
        if (s.isDown()) {
            metric = ServerMetric.down(serverId);
        } else if (wasDown) {
            metric = ServerMetric.initial(serverId);
        }
        if (metric != null && mapper.updateMetric(metric) == 0) {
            mapper.insertMetric(metric);
        }
        alarmService.checkAfterCommit(serverId);
        log.info("SERVER UPDATE id={} name={} status={}", serverId, s.getSystemNm(), s.getStatusCd());
        return getServer(serverId);
    }

    @Transactional
    public void deleteServer(Long serverId) {
        Server s = findServer(serverId);
        mapper.clearPairReference(serverId);
        // 서버를 참조하는 데이터 먼저 삭제 (에이전트, 서비스/제어 명령, 수집 이력)
        serviceCtlMapper.deleteCommandsByServer(serverId);
        serviceCtlMapper.deleteServiceHistByServer(serverId);
        serviceCtlMapper.deleteServicesByServer(serverId);
        alarmMapper.deleteAlarmsByServer(serverId);
        serverLogMapper.deleteSnapshotsByServer(serverId);
        serverLogMapper.deleteSourcesByServer(serverId);
        serverLogMapper.deletePolicy(serverId);
        reportMapper.deleteReportsByServer(serverId);
        agentMapper.deleteFallback(serverId);
        agentMapper.deleteHbHistByServer(serverId);
        agentMapper.deleteAgent(serverId);
        mapper.deleteProcessHistByServer(serverId);
        mapper.deleteMetricHistByServer(serverId);
        mapper.deleteProcessesByServer(serverId);
        mapper.deleteMetric(serverId);
        mapper.deleteServer(serverId);
        log.info("SERVER DELETE id={} name={}", serverId, s.getSystemNm());
    }

    /**
     * 서버 데이터 초기화 (ADMIN) : 서버 등록·주요 프로세스·service.list·로그 수집 설정은 두고
     * 에이전트 토큰과 수집·운영 데이터(자원·프로세스·서비스 이력, 하트비트 이력, 대신 수집, 제어 명령, 수집 로그, 알람)를 지우고
     * 현재 값(자원·프로세스·서비스 상태)을 처음 상태로 되돌림.
     * activate=true 이고 등록 상태가 Down 이면 Active 로 바꿈 (메인 홈 "서버 장애" 항목 해소).
     */
    @Transactional
    public ServerView resetServerData(Long serverId, boolean activate, String user) {
        Server s = findServer(serverId);
        serviceCtlMapper.deleteCommandsByServer(serverId);
        serviceCtlMapper.deleteServiceHistByServer(serverId);
        serviceCtlMapper.resetServiceStatesByServer(serverId);
        alarmMapper.deleteAlarmsByServer(serverId);
        serverLogMapper.deleteSnapshotsByServer(serverId);
        reportMapper.deleteReportsByServer(serverId);
        agentMapper.deleteFallback(serverId);
        agentMapper.deleteHbHistByServer(serverId);
        agentMapper.deleteAgent(serverId);
        mapper.deleteProcessHistByServer(serverId);
        mapper.deleteMetricHistByServer(serverId);
        mapper.resetProcessesByServer(serverId);
        boolean statusChanged = false;
        if (activate && s.isDown()) {
            s.setStatusCd("Active");
            s.setSyncStatus(syncStatusOf("Active"));
            mapper.updateServer(s);
            statusChanged = true;
        }
        ServerMetric metric = s.isDown() ? ServerMetric.down(serverId) : ServerMetric.initial(serverId);
        if (mapper.updateMetric(metric) == 0) mapper.insertMetric(metric);
        alarmService.checkAfterCommit(serverId);
        log.info("SERVER DATA RESET id={} name={} by {} (token·이력·알람 삭제{})", serverId, s.getSystemNm(), user,
                statusChanged ? ", 상태 Down → Active" : "");
        return getServer(serverId);
    }

    /** 기본 서버 목록으로 초기화 (db/data.sql 실행, 같은 트랜잭션) */
    @Transactional
    public List<ServerView> resetToDefaults() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(SEED_SCRIPT));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
        log.info("SERVER RESET to defaults ({})", SEED_SCRIPT);
        return getServers();
    }

    // ===================== 프로세스 =====================

    @Transactional
    public ProcessView addProcess(Long serverId, ProcessForm form) {
        findServer(serverId);
        ServerProcess p = new ServerProcess();
        p.setServerId(serverId);
        p.setProcNm(form.getName().trim());
        p.setExecPath(form.getPath().trim());
        applyRuntime(p, form.isRunning());
        p.setSortOrd(mapper.selectMaxProcessSortOrd(serverId) + 10);
        mapper.insertProcess(p);
        return ProcessView.of(mapper.selectProcess(serverId, p.getProcId()));
    }

    @Transactional
    public ProcessView updateProcess(Long serverId, Long procId, ProcessForm form) {
        ServerProcess p = findProcess(serverId, procId);
        p.setProcNm(form.getName().trim());
        p.setExecPath(form.getPath().trim());
        if (form.isRunning() && p.getPid() == null) {
            applyRuntime(p, true);          // 중지 → 실행
        } else if (!form.isRunning()) {
            applyRuntime(p, false);         // 실행 → 중지
        }
        mapper.updateProcess(p);
        return ProcessView.of(mapper.selectProcess(serverId, procId));
    }

    /** 기동 ↔ 중지 */
    @Transactional
    public ProcessView toggleProcess(Long serverId, Long procId) {
        ServerProcess p = findProcess(serverId, procId);
        applyRuntime(p, !p.isRunning());
        mapper.updateProcess(p);
        return ProcessView.of(mapper.selectProcess(serverId, procId));
    }

    @Transactional
    public void deleteProcess(Long serverId, Long procId) {
        findProcess(serverId, procId);
        mapper.deleteProcessHistByProcess(procId);
        mapper.deleteProcess(serverId, procId);
    }

    // ===================== 수집 이력 (메인 홈 서버별 그래프) =====================

    /** 서버별 최근 points 건의 자원/프로세스 이력 */
    public Map<Long, HistoryView> getHistory(int points) {
        int n = Math.max(2, Math.min(points, 1440));   // 메인 홈 대시보드 24시간 (1분 수집 기준)
        Map<Long, HistoryView> map = new LinkedHashMap<>();
        for (MetricHist h : lastN(mapper.selectMetricHistory(n), MetricHist::getServerId, n)) {
            map.computeIfAbsent(h.getServerId(), k -> new HistoryView()).addServerPoint(h);
        }
        for (MetricHist h : lastN(mapper.selectProcessHistory(n), MetricHist::getProcId, n)) {
            map.computeIfAbsent(h.getServerId(), k -> new HistoryView()).addProcessPoint(h);
        }
        // 프로세스 관리(service.list) 서비스 CPU 이력 - 메인 홈에서 골라서 그래프로 보기
        for (com.scb.monitoring.svcctl.domain.ServiceMetricHist h
                : lastN(serviceCtlMapper.selectServiceHistory(n), com.scb.monitoring.svcctl.domain.ServiceMetricHist::getSvcId, n)) {
            map.computeIfAbsent(h.getServerId(), k -> new HistoryView()).addServicePoint(h);
        }
        return map;
    }

    /**
     * 키(서버·프로세스·서비스)별로 정렬된 이력에서 키마다 마지막 n 건만 (순서 유지).
     * ROW_NUMBER 로 이미 n 건씩 받은 DB 는 그대로이고, Sybase(최근 24시간 전체를 받음)는 여기서 자름.
     */
    public static <T> List<T> lastN(List<T> rows, java.util.function.Function<T, Object> key, int n) {
        Map<Object, Integer> total = new java.util.HashMap<>();
        for (T r : rows) total.merge(key.apply(r), 1, Integer::sum);
        if (total.values().stream().allMatch(c -> c <= n)) return rows;
        Map<Object, Integer> seen = new java.util.HashMap<>();
        List<T> out = new java.util.ArrayList<>();
        for (T r : rows) {
            Object k = key.apply(r);
            int i = seen.merge(k, 1, Integer::sum);
            if (i > total.get(k) - n) out.add(r);
        }
        return out;
    }

    // ===================== 내부 =====================

    private Server findServer(Long serverId) {
        Server s = mapper.selectServer(serverId);
        if (s == null) throw TopologyException.notFound("서버를 찾을 수 없습니다. (id=" + serverId + ")");
        return s;
    }

    private ServerProcess findProcess(Long serverId, Long procId) {
        ServerProcess p = mapper.selectProcess(serverId, procId);
        if (p == null) throw TopologyException.notFound("프로세스를 찾을 수 없습니다. (server=" + serverId + ", id=" + procId + ")");
        return p;
    }

    private void checkSwitch(Integer nodeId) {
        NetNode n = mapper.selectNode(nodeId);
        if (n == null || !n.isSwitch()) {
            throw TopologyException.badRequest("설치장소(스위치)가 올바르지 않습니다. (node=" + nodeId + ")");
        }
    }

    private void checkDuplicateIp(String ip, Long excludeServerId) {
        if (mapper.countServerByIp(ip.trim(), excludeServerId) > 0) {
            throw TopologyException.conflict("이미 등록된 IP 주소입니다: " + ip.trim());
        }
    }

    private static void apply(Server s, ServerForm f) {
        s.setSystemNm(f.getSystemName().trim());
        s.setFqdn(f.getFqdn().trim());
        s.setOsType(f.getOs() == null || f.getOs().trim().isEmpty() ? "SOLARIS" : f.getOs().trim());
        s.setIpAddr(f.getIp().trim());
        s.setSwitchNodeId(f.getSwitchNodeId());
        s.setStatusCd(f.getStatus());
        s.setSyncStatus(syncStatusOf(f.getStatus()));
    }

    static String syncStatusOf(String status) {
        if (Server.STATUS_ACTIVE.equals(status)) return "Synchronized (OK)";
        if (Server.STATUS_DOWN.equals(status)) return "DISCONNECTED (Link Down)";
        return "Standby Mirror Ready";
    }

    private void addDefaultProcess(Server s, String name, String path, int sortOrd) {
        ServerProcess p = new ServerProcess();
        p.setServerId(s.getServerId());
        p.setProcNm(name);
        p.setExecPath(path);
        applyRuntime(p, !s.isDown());
        p.setSortOrd(sortOrd);
        mapper.insertProcess(p);
    }

    /** 실행 상태에 맞는 PID / CPU / MEM (화면 시뮬레이션 값) */
    private static void applyRuntime(ServerProcess p, boolean running) {
        p.setRunning(running);
        if (running) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            p.setPid(10000 + r.nextInt(20000));
            p.setCpuPct(BigDecimal.valueOf(r.nextInt(0, 50), 1));      // 0.0 ~ 4.9
            p.setMemPct(BigDecimal.valueOf(r.nextInt(20, 120), 1));    // 2.0 ~ 11.9
        } else {
            p.setPid(null);
            p.setCpuPct(BigDecimal.ZERO.setScale(1));
            p.setMemPct(BigDecimal.ZERO.setScale(1));
        }
    }
}
