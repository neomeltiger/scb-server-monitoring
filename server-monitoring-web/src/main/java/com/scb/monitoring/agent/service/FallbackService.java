package com.scb.monitoring.agent.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.domain.ProcFallback;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.dto.FallbackView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.svcctl.domain.ServiceItem;
import com.scb.monitoring.svcctl.mapper.ServiceCtlMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerProcess;
import com.scb.monitoring.topology.mapper.TopologyMapper;

/**
 * 데몬 중단 중 프로세스 현황 (tb_proc_fallback, 화면 /fallback).
 * <ul>
 *   <li>데몬(scb_client)이 죽거나 멈춰도 서버의 프로세스는 살아 있을 수 있음 → 하트비트 감시자(클라이언트 1.7~)가
 *       collect_metric / collect_proc / collect_service 로 대신 수집해 POST /agent-api/v1/fallback (30초마다)</li>
 *   <li>데몬이 멈춰 있을 때만 받음 (데몬이 돌고 있으면 무시) - 서버당 1건, 받을 때마다 덮어씀</li>
 *   <li>데몬이 다시 시작되면(감시자가 RUNNING 을 알리거나 데몬이 웹을 확인·보고하면) 삭제 → 기존 화면이 데몬 정보로 돌아감</li>
 *   <li>기존 화면(메인 홈·구성도·프로세스 관리)은 데몬 기준 그대로 (데몬이 멈추면 종료 표시) - 이 화면만 실제 현황을 보여 줌</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FallbackService {

    private static final Logger hbLog = LoggerFactory.getLogger("heartbeat");
    /** 감시자 수집 주기(30초)의 3배 넘게 새 정보가 없으면 "수집 지연" */
    public static final int STALE_SEC = 90;

    private final AgentMapper mapper;
    private final TopologyMapper topologyMapper;
    private final ServiceCtlMapper serviceCtlMapper;
    private final AgentProperties props;
    /** 하트비트가 알린 감시자 버전 / 대신 수집 결과 (OK, WAIT, FAIL_코드, FAIL_TIMEOUT) - 서버 ID → {버전, 결과} */
    private final Map<Long, String[]> watchdog = new ConcurrentHashMap<>();

    public FallbackService(AgentMapper mapper, TopologyMapper topologyMapper, ServiceCtlMapper serviceCtlMapper, AgentProperties props) {
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.serviceCtlMapper = serviceCtlMapper;
        this.props = props;
    }

    /**
     * 감시자가 보낸 수집 결과 저장. 데몬이 멈춰 있을 때만.
     * @return 저장했으면 true, 데몬이 돌고 있거나 에이전트가 없으면 false
     */
    @Transactional
    public boolean ingest(Long serverId, String text) {
        Agent a = mapper.selectAgent(serverId);
        if (a == null || !a.isDaemonDown()) return false;
        AgentReport r = AgentReport.parse(text == null ? "" : text);
        ProcFallback f = new ProcFallback();
        f.setServerId(serverId);
        f.setDaemonState(a.isWatchdogClient() ? a.getHbDaemonState() : Agent.HD_STOPPED);
        f.setReportText(text.length() > 200000 ? text.substring(0, 200000) : text);
        f.setProcCnt(r.procCount());
        f.setSvcCnt(r.serviceStates().size());
        if (mapper.updateFallback(f) == 0) {
            mapper.insertFallback(f);
            hbLog.info("HEARTBEAT FALLBACK server=" + serverId + " [" + serverName(serverId) + "] : 데몬 중단 - 감시자가 프로세스 정보 대신 수집 시작 (프로세스 "
                    + f.getProcCnt() + ", 서비스 " + f.getSvcCnt() + ")");
        }
        return true;
    }

    /** 데몬이 다시 시작됨 → 대신 수집한 정보 삭제 */
    @Transactional
    public boolean clear(Long serverId, String why) {
        if (mapper.deleteFallback(serverId) == 0) return false;
        hbLog.info("HEARTBEAT FALLBACK server=" + serverId + " [" + serverName(serverId) + "] : " + why + " - 데몬 중단 중 프로세스 현황 삭제");
        return true;
    }

    /** 하트비트 수신 때 : 감시자 버전·대신 수집 결과 기록 (화면에서 정보가 없는 원인 표시용) */
    public void noteWatchdog(Long serverId, String hbVer, String fbStat) {
        String v = hbVer == null ? null : (hbVer.length() > 10 ? hbVer.substring(0, 10) : hbVer.trim());
        String f = fbStat == null ? null : (fbStat.length() > 20 ? fbStat.substring(0, 20) : fbStat.trim());
        watchdog.put(serverId, new String[] {v, f});
    }

    /** 화면 /fallback : 대신 수집 중인 서버 + 데몬이 멈췄는데 아직 정보가 없는 서버(원인 표시) */
    public List<FallbackView> getList() {
        List<FallbackView> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        java.util.Set<Long> have = new java.util.HashSet<>();
        for (ProcFallback f : mapper.selectFallbackList()) {
            have.add(f.getServerId());
            Server s = topologyMapper.selectServer(f.getServerId());
            if (s == null) continue;
            AgentView av = AgentView.of(mapper.selectAgent(f.getServerId()), props.getStaleAfterSec());
            Map<Long, ServerProcess> procs = new HashMap<>();
            for (ServerProcess p : topologyMapper.selectProcessListByServer(f.getServerId())) procs.put(p.getProcId(), p);
            Map<String, ServiceItem> svcs = new HashMap<>();
            List<String> svcOrder = new ArrayList<>();
            for (ServiceItem it : serviceCtlMapper.selectServiceList(f.getServerId())) {
                svcs.put(it.getSvcNm(), it);
                svcOrder.add(it.getSvcNm());
            }
            FallbackView v = FallbackView.of(s, f, AgentReport.parse(f.getReportText()), av, procs, svcs, svcOrder, now, STALE_SEC);
            String[] w = watchdog.get(f.getServerId());
            if (w != null) v.setWatchdog(w[0], w[1]);
            out.add(v);
        }
        // 하트비트는 오는데(서버 정상) 데몬이 멈췄고 아직 대신 수집한 정보가 없는 서버
        for (Agent a : mapper.selectAgentList()) {
            if (have.contains(a.getServerId())) continue;
            AgentView av = AgentView.of(a, props.getStaleAfterSec());
            if (!"STOPPED".equals(av.getState()) || !Agent.HB_ALIVE.equals(av.getHbState())) continue;
            Server s = topologyMapper.selectServer(a.getServerId());
            if (s == null) continue;
            String[] w = watchdog.get(a.getServerId());
            out.add(FallbackView.pending(s, av, w != null, w == null ? null : w[0], w == null ? null : w[1], a.isWatchdogClient()));
        }
        return out;
    }

    private String serverName(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        return s == null ? String.valueOf(serverId) : s.getSystemNm();
    }
}
