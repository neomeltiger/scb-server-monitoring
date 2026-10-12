package com.scb.monitoring.agent.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.domain.HeartbeatHist;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.dto.HeartbeatView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 하트비트 : 원격 서버(에이전트 데몬)가 살아 있는지 확인.
 * <ul>
 *   <li>수신 : 클라이언트 1.4 bin/scb_heartbeat.sh 가 hb_interval_sec 마다 GET /agent-api/v1/heartbeat
 *       (이전 데몬 1.2 / 1.3 은 웹 확인 /commands 를 하트비트로 사용)</li>
 *   <li>감시 : app.agent.heartbeat-check-ms (기본 2초) 마다 - 마지막 수신 후 끊김 기준(주기 x 2 + 3초) 이 지나면 LOST
 *       → 그 서버의 실행 중 프로세스·서비스를 즉시 종료로 표시 (ProcessStatusService) + 알람 점검</li>
 *   <li>데몬 종료 보고(POST /daemon?state=STOPPED) → STOPPED, 같은 방식으로 즉시 종료 표시</li>
 *   <li>주기 변경 : 네트워크 구성도 서버 상세 (ADMIN) → 다음 하트비트 응답(hb|interval=N)으로 클라이언트에 전달</li>
 *   <li>이벤트(수신 시작 / 끊김 / 복구 / 데몬 종료 / 주기 변경)는 tb_heartbeat_hist 와 logs/heartbeat.log 에 기록</li>
 * </ul>
 */
@Service
public class HeartbeatService {

    /** 하트비트 이벤트 전용 로거 (logback-spring.xml → logs/heartbeat.log) */
    static final Logger hbLog = LoggerFactory.getLogger("heartbeat");
    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final AgentMapper mapper;
    private final TopologyMapper topologyMapper;
    private final ProcessStatusService processStatus;
    private final AlarmService alarmService;
    private final AgentProperties props;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ReentrantLock lock = new ReentrantLock();

    public HeartbeatService(AgentMapper mapper, TopologyMapper topologyMapper, ProcessStatusService processStatus,
                            AlarmService alarmService, AgentProperties props, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.processStatus = processStatus;
        this.alarmService = alarmService;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ===================== 수신 =====================

    /**
     * 하트비트 수신 (클라이언트 1.4). 끊겼다가 다시 오면 RECOVER, 처음·데몬 재시작이면 START.
     * @return 클라이언트에 줄 응답 : hb|interval=10|timeout=23
     */
    public String beat(Long serverId, Integer pid) {
        Boolean recovered = tx.execute(st -> {
            Agent a = mapper.selectAgent(serverId);
            if (a == null) return false;
            String before = a.getHbState();
            Long gap = a.getLastHbDt() == null ? null : (System.currentTimeMillis() - a.getLastHbDt().getTime()) / 1000;
            mapper.updateHbBeat(serverId, pid);
            if (!Agent.HB_ALIVE.equals(before)) {
                boolean lost = Agent.HB_LOST.equals(before);
                event(serverId, lost ? HeartbeatHist.E_RECOVER : HeartbeatHist.E_START, pid,
                        lost ? gap : null, lost ? "하트비트 다시 수신" : "하트비트 수신 시작 (주기 " + a.getHbIntervalSec() + "초)");
                return lost;
            }
            return false;
        });
        if (Boolean.TRUE.equals(recovered)) alarmService.checkAfterCommit(serverId);
        Agent a = readTx.execute(st -> mapper.selectAgent(serverId));
        int interval = a == null ? Agent.DEFAULT_HB_INTERVAL : a.getHbIntervalSec();
        int timeout = interval * 2 + 3;
        return "hb|interval=" + interval + "|timeout=" + timeout + "\n";
    }

    /**
     * 하트비트 셸이 없는 이전 데몬(1.2 / 1.3) : 웹 확인(/commands)을 생존 신호로 사용 - 상태 변화만 기록.
     * AgentService.pullCommands 의 트랜잭션 안에서 호출.
     */
    void legacyBeat(Agent before, Integer pid, String ver) {
        if (before == null || before.isHeartbeatClient() || Agent.HB_ALIVE.equals(before.getHbState())) return;
        if (hasHeartbeatShell(ver)) return;     // 1.4~ : 하트비트 셸이 곧 보냄 (웹 확인이 먼저 와도 기다림)
        boolean lost = Agent.HB_LOST.equals(before.getHbState());
        mapper.updateHbState(before.getServerId(), Agent.HB_ALIVE);
        event(before.getServerId(), lost ? HeartbeatHist.E_RECOVER : HeartbeatHist.E_START, pid, null,
                (lost ? "웹 확인 다시 수신" : "웹 확인 수신 시작") + " (하트비트 셸 없는 이전 클라이언트)");
    }

    /** 클라이언트 1.4 이상 = 하트비트 셸(bin/scb_heartbeat.sh) 있음 */
    static boolean hasHeartbeatShell(String ver) {
        if (ver == null) return false;
        String[] p = ver.trim().split("\\.");
        try {
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9].*$", "")) : 0;
            return major > 1 || (major == 1 && minor >= 4);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 데몬 종료 보고 : 감시 프로세스·서비스 즉시 종료 표시. AgentService.daemonStopped 트랜잭션 안에서 호출 */
    void daemonStopped(Long serverId) {
        Agent a = mapper.selectAgent(serverId);
        mapper.updateHbState(serverId, Agent.HB_STOPPED);
        event(serverId, HeartbeatHist.E_STOP, a == null ? null : a.getDaemonPid(), null, "데몬 종료 보고");
        processStatus.stopAllNoData(serverId, serverName(serverId), "데몬 종료");
    }

    // ===================== 감시 =====================

    @Scheduled(initialDelayString = "${app.agent.heartbeat-initial-delay-ms:5000}",
               fixedDelayString = "${app.agent.heartbeat-check-ms:2000}")
    public void scheduledWatch() {
        try {
            watch();
        } catch (RuntimeException e) {
            log.error("하트비트 감시 실패", e);
        }
    }

    /**
     * 끊김 판정 : 하트비트(또는 이전 데몬의 웹 확인)가 기준 시간 넘게 없으면 LOST 로 바꾸고 즉시 종료 표시.
     * @return 이번에 끊김으로 바뀐 서버 수
     */
    public int watch() {
        if (!lock.tryLock()) return 0;
        try {
            List<Long> lostIds = tx.execute(st -> {
                List<Long> ids = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Agent a : mapper.selectAgentList()) {
                    String hb = a.heartbeatState(now);
                    if (Agent.HB_LOST.equals(hb) && !Agent.HB_LOST.equals(a.getHbState())) {
                        mapper.updateHbState(a.getServerId(), Agent.HB_LOST);
                        Long gap = a.heartbeatAgoSec(now);
                        event(a.getServerId(), HeartbeatHist.E_LOST, a.isHeartbeatClient() ? a.getHbPid() : a.getDaemonPid(),
                                gap == null ? null : gap.intValue(),
                                "하트비트 끊김 - " + gap + "초 동안 수신 없음 (기준 " + a.heartbeatTimeoutSec() + "초)");
                        processStatus.stopAllNoData(a.getServerId(), serverName(a.getServerId()), "하트비트 끊김");
                        ids.add(a.getServerId());
                    }
                }
                return ids;
            });
            if (lostIds == null) return 0;
            for (Long id : lostIds) alarmService.checkServer(id);
            return lostIds.size();
        } finally {
            lock.unlock();
        }
    }

    // ===================== 설정 / 조회 =====================

    /** 하트비트 주기 변경 (ADMIN, 네트워크 구성도) - 다음 하트비트 응답으로 클라이언트에 전달 */
    public AgentView changeInterval(Long serverId, int sec, String user) {
        if (!Agent.isAllowedHbInterval(sec)) {
            throw TopologyException.badRequest("하트비트 주기는 3 / 5 / 10 / 15 / 30 / 60 초 중에서 고르세요.");
        }
        return tx.execute(st -> {
            Agent a = mapper.selectAgent(serverId);
            if (a == null) throw TopologyException.badRequest("에이전트 토큰을 먼저 발급하세요. (server_id=" + serverId + ")");
            if (a.getHbIntervalSec() != sec) {
                mapper.updateHbInterval(serverId, sec, user);
                event(serverId, HeartbeatHist.E_INTERVAL, null, null, a.getHbIntervalSec() + "초 → " + sec + "초 (" + user + ")");
            }
            return AgentView.of(mapper.selectAgent(serverId), props.getStaleAfterSec());
        });
    }

    /** 하트비트 모니터링 화면 / 화면 즉시 갱신용 (가벼운 조회) */
    public List<HeartbeatView> getStatusList() {
        return readTx.execute(st -> {
            Map<Long, Agent> agents = new HashMap<>();
            for (Agent a : mapper.selectAgentList()) agents.put(a.getServerId(), a);
            List<HeartbeatView> list = new ArrayList<>();
            for (Server s : topologyMapper.selectServerList()) {
                list.add(HeartbeatView.of(s, agents.get(s.getServerId()), props.getStaleAfterSec()));
            }
            return list;
        });
    }

    public List<HeartbeatHist> getEvents(Long serverId, int rows) {
        int n = Math.max(1, Math.min(rows, 500));
        return readTx.execute(st -> mapper.selectHbHist(serverId, n));
    }

    /** 보관 기간 지난 이벤트 정리 (에이전트 보고 때) */
    void cleanup(Long serverId, Timestamp before) {
        mapper.deleteHbHistBefore(serverId, before);
    }

    // ===================== 내부 =====================

    private void event(Long serverId, String eventCd, Integer pid, Number gapSec, String detail) {
        String d = detail == null ? null : (detail.length() > 200 ? detail.substring(0, 200) : detail);
        mapper.insertHbHist(serverId, eventCd, pid, gapSec == null ? null : gapSec.intValue(), d);
        String line = "HEARTBEAT " + eventCd + " server=" + serverId + " [" + serverName(serverId) + "] pid="
                + (pid == null ? "-" : pid) + (gapSec == null ? "" : " gap=" + gapSec + "s") + " : " + d;
        if (HeartbeatHist.E_LOST.equals(eventCd)) hbLog.warn(line);
        else hbLog.info(line);
    }

    private String serverName(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        return s == null ? String.valueOf(serverId) : s.getSystemNm();
    }
}
