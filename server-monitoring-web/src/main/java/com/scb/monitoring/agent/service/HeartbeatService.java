package com.scb.monitoring.agent.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 하트비트 : 원격 서버가 살아 있는지 확인하고, 서버 다운과 데몬 문제를 구분.
 * <ul>
 *   <li>수신 : 클라이언트 bin/scb_heartbeat.sh 가 hb_interval_sec 마다 GET /agent-api/v1/heartbeat?pid=&amp;daemon=
 *       - 1.5~ 는 데몬과 따로 도는 감시자라 데몬이 죽어도 계속 보내며 데몬 상태(RUNNING / PAUSED / STOPPED / DEAD)를 알림
 *       (1.4 는 데몬과 함께 멈춤, 이전 데몬 1.2 / 1.3 은 웹 확인 /commands 를 하트비트로 사용)</li>
 *   <li>서버 다운 : app.agent.heartbeat-check-ms (기본 2초) 마다 감시 - <b>3회 연속 미수신</b>(주기 x 3 + 2초) 이면 LOST
 *       → 그 서버의 실행 중 프로세스·서비스를 즉시 종료 + 서버 Down + 알람</li>
 *   <li>데몬 문제 : 하트비트는 오는데 데몬이 DEAD / STOPPED → 서버는 정상(Down 아님), 프로세스·서비스 종료 표시,
 *       데몬 중단 알람(DAEMON_DOWN) + 웹 [데몬 재시작] → 다음 하트비트 응답 restart=Y → 감시자가 scb_client.sh start</li>
 *   <li>서버 복구 자동 감지 : 끊겼던(LOST) 하트비트가 다시 오거나 부팅 시각이 바뀌면(재부팅)
 *       → 데몬이 멈춰 있으면 자동 재시작(restart=Y, 일부러 멈춘 경우 제외), 데몬이 돌고 있으면 즉시 보고 요청(ctl report=Y)
 *       → 다음 보고로 서버 자원·프로세스 정보가 바로 복구됨</li>
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
    private final FallbackService fallback;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ReentrantLock lock = new ReentrantLock();
    /** 서버 복구 후 데몬에 즉시 보고를 요청할 서버 (다음 웹 확인 응답 ctl|...|report=Y 로 한 번 전달) */
    private final Set<Long> reportNow = ConcurrentHashMap.newKeySet();

    /** 토큰 제거·서버 데이터 초기화 뒤 메모리 상태 정리 */
    public void forget(Long serverId) {
        reportNow.remove(serverId);
    }

    public HeartbeatService(AgentMapper mapper, TopologyMapper topologyMapper, ProcessStatusService processStatus,
                            AlarmService alarmService, AgentProperties props, PlatformTransactionManager txManager,
                            FallbackService fallback) {
        this.fallback = fallback;
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

    /** 1.4 호환 (데몬 상태를 보내지 않는 하트비트) */
    public String beat(Long serverId, Integer pid) {
        return beat(serverId, pid, null);
    }

    /** 1.5 호환 (부팅 정보를 보내지 않는 하트비트) */
    public String beat(Long serverId, Integer pid, String daemonState) {
        return beat(serverId, pid, daemonState, null, null);
    }

    /** 데몬의 웹 확인 때 : 서버 복구 직후면 true (한 번만) → ctl|...|report=Y */
    public boolean consumeReportNow(Long serverId) {
        return reportNow.remove(serverId);
    }

    /**
     * 하트비트 수신. 끊겼다가 다시 오면 RECOVER, 처음이면 START.
     * daemonState (1.5~ 감시자) : RUNNING / PAUSED → 데몬 정상, STOPPED / DEAD → 데몬 중단(D_DOWN, 서버는 정상)
     * @return 클라이언트에 줄 응답 : hb|interval=10|timeout=32|restart=N  (restart=Y 면 감시자가 데몬을 시작)
     */
    public String beat(Long serverId, Integer pid, String daemonState, String bootAuto, String bootTime) {
        String ds = normalizeDaemonState(daemonState);
        String ba = "Y".equals(bootAuto) || "N".equals(bootAuto) ? bootAuto : null;
        String bt = bootTime == null || bootTime.trim().isEmpty() ? null
                : (bootTime.trim().length() > 30 ? bootTime.trim().substring(0, 30) : bootTime.trim());
        String[] restart = {"N"};
        Boolean changed = tx.execute(st -> {
            Agent a = mapper.selectAgent(serverId);
            if (a == null) return false;
            boolean alarmCheck = false;
            String before = a.getHbState();
            String prevDaemon = a.getHbDaemonState();
            Long gap = a.getLastHbDt() == null ? null : (System.currentTimeMillis() - a.getLastHbDt().getTime()) / 1000;
            mapper.updateHbBeat(serverId, pid, ds, ba, bt);
            boolean rebooted = bt != null && a.getBootTime() != null && !bt.equals(a.getBootTime());
            boolean lost = Agent.HB_LOST.equals(before);
            if (!Agent.HB_ALIVE.equals(before)) {
                event(serverId, lost ? HeartbeatHist.E_RECOVER : HeartbeatHist.E_START, pid, lost ? gap : null,
                        lost ? "하트비트 다시 수신 - 서버 복구 감지" + (rebooted ? " (재부팅됨, 부팅 " + bt + ")" : "")
                             : "하트비트 수신 시작 (주기 " + a.getHbIntervalSec() + "초)" + (rebooted ? " - 서버 재부팅 감지 (부팅 " + bt + ")" : ""));
                alarmCheck = lost;
            } else if (rebooted) {
                event(serverId, HeartbeatHist.E_RECOVER, pid, null, "서버 재부팅 감지 (부팅 " + bt + ") - 끊김 기준 안에 다시 연결됨");
            }
            boolean recovered = lost || rebooted;
            if (recovered) reportNow.add(serverId);     // 데몬이 돌고 있으면 다음 웹 확인 때 바로 보고
            if (ds != null) {
                boolean down = Agent.HD_DEAD.equals(ds) || Agent.HD_STOPPED.equals(ds);
                boolean wasDown = Agent.HD_DEAD.equals(prevDaemon) || Agent.HD_STOPPED.equals(prevDaemon);
                if (down && !wasDown) {
                    // 데몬만 멈춤 : 서버는 정상 - 감시 프로세스는 정보가 없으므로 종료 표시, 재시작 알림
                    boolean dead = Agent.HD_DEAD.equals(ds);
                    event(serverId, HeartbeatHist.E_D_DOWN, a.getDaemonPid(), null,
                            dead ? "데몬 비정상 종료 감지 - 서버는 정상 (하트비트 수신 중), 데몬 재시작 필요"
                                 : "데몬 정지 - 서버는 정상 (하트비트 수신 중)");
                    processStatus.stopAllNoData(serverId, serverName(serverId), dead ? "데몬 비정상 종료" : "데몬 종료");
                    alarmCheck = true;
                } else if (!down && wasDown) {
                    event(serverId, HeartbeatHist.E_D_UP, pid, null, "데몬 다시 실행 (pid " + (pid == null ? "-" : pid) + ")"
                            + (a.isRestartPending() ? " - 웹 재시작 요청 처리됨" : ""));
                    fallback.clear(serverId, "데몬 다시 실행");
                    alarmCheck = true;
                }
                // 서버 복구 직후 데몬이 멈춰 있으면 자동 재시작 (관리자가 일부러 멈춘 경우 제외)
                if (recovered && down && !a.isDaemonStopIntended() && !a.isRestartPending()) {
                    restart[0] = "Y";
                    mapper.updateRestartReq(serverId, "Y", "auto");
                    mapper.updateRestartReq(serverId, "S", null);
                    event(serverId, HeartbeatHist.E_RESTART, a.getDaemonPid(), null,
                            "데몬 자동 재시작 - 서버 복구 후 데몬이 실행되지 않음" + (rebooted ? " (재부팅)" : ""));
                }
                // 재시작 요청 : 데몬이 멈춰 있으면 이번 응답으로 전달(S), 데몬이 돌고 있으면 요청 해제
                if ("Y".equals(a.getRestartReqYn()) && down) {
                    restart[0] = "Y";
                    mapper.updateRestartReq(serverId, "S", null);
                    hbLog.info("HEARTBEAT RESTART server=" + serverId + " [" + serverName(serverId) + "] : 재시작 명령을 감시자에 전달");
                } else if (a.isRestartPending() && !down) {
                    mapper.updateRestartReq(serverId, "N", null);
                }
            }
            return alarmCheck;
        });
        if (Boolean.TRUE.equals(changed)) alarmService.checkAfterCommit(serverId);
        Agent a = readTx.execute(st -> mapper.selectAgent(serverId));
        int interval = a == null ? Agent.DEFAULT_HB_INTERVAL : a.getHbIntervalSec();
        int timeout = interval * Agent.HB_MISS_LIMIT + Agent.HB_GRACE_SEC;
        return "hb|interval=" + interval + "|timeout=" + timeout + "|restart=" + restart[0] + "\n";
    }

    private static String normalizeDaemonState(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase();
        return Agent.HD_RUNNING.equals(v) || Agent.HD_PAUSED.equals(v) || Agent.HD_STOPPED.equals(v) || Agent.HD_DEAD.equals(v) ? v : null;
    }

    /**
     * 하트비트 셸이 없는 이전 데몬(1.2 / 1.3) : 웹 확인(/commands)을 생존 신호로 사용 - 상태 변화만 기록.
     * AgentService.pullCommands 의 트랜잭션 안에서 호출.
     */
    void legacyBeat(Agent before, Integer pid, String ver) {
        daemonPolled(before, pid);
        if (before == null || before.isHeartbeatClient() || Agent.HB_ALIVE.equals(before.getHbState())) return;
        if (hasHeartbeatShell(ver)) return;     // 1.4~ : 하트비트 셸이 곧 보냄 (웹 확인이 먼저 와도 기다림)
        boolean lost = Agent.HB_LOST.equals(before.getHbState());
        if (lost) reportNow.add(before.getServerId());     // 서버 복구 : 바로 보고 요청
        mapper.updateHbState(before.getServerId(), Agent.HB_ALIVE);
        event(before.getServerId(), lost ? HeartbeatHist.E_RECOVER : HeartbeatHist.E_START, pid, null,
                (lost ? "웹 확인 다시 수신" : "웹 확인 수신 시작") + " (하트비트 셸 없는 이전 클라이언트)");
    }

    /**
     * 데몬이 웹을 확인함 = 데몬은 실행 중. 감시자(1.5~)가 아직 다음 하트비트로 알리기 전이라도 바로 "데몬 다시 실행" 으로 반영
     * (재시작 직후 데몬의 첫 보고가 "데몬 중단" 상태로 처리되어 프로세스가 다시 종료로 바뀌지 않게)
     */
    private void daemonPolled(Agent before, Integer pid) {
        if (before == null || !before.isWatchdogClient() || !before.isDaemonDown()) return;
        mapper.updateHbDaemonState(before.getServerId(), Agent.HD_RUNNING);
        event(before.getServerId(), HeartbeatHist.E_D_UP, pid, null, "데몬 다시 실행 (pid " + (pid == null ? "-" : pid) + ")"
                + (before.isRestartPending() ? " - 웹 재시작 요청 처리됨" : ""));
        if (before.isRestartPending()) mapper.updateRestartReq(before.getServerId(), "N", null);
        fallback.clear(before.getServerId(), "데몬 다시 실행");
    }

    /** 데몬이 보고를 보냄 = 데몬 실행 중 (감시자의 다음 하트비트 전이라도 바로 반영). AgentService.ingestReport 트랜잭션 안에서 호출 */
    void daemonReported(Long serverId) {
        daemonPolled(mapper.selectAgent(serverId), null);
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

    /**
     * 데몬 종료 보고 : 감시 프로세스·서비스 즉시 종료 표시. AgentService.daemonStopped 트랜잭션 안에서 호출.
     * 감시자(1.5~)가 있으면 하트비트는 계속 오므로 서버는 정상 - 데몬 상태만 STOPPED 로.
     */
    void daemonStopped(Long serverId) {
        Agent a = mapper.selectAgent(serverId);
        boolean watchdog = a != null && a.isWatchdogClient();
        if (watchdog) {
            mapper.updateHbDaemonState(serverId, Agent.HD_STOPPED);
        } else {
            mapper.updateHbState(serverId, Agent.HB_STOPPED);
        }
        event(serverId, HeartbeatHist.E_STOP, a == null ? null : a.getDaemonPid(), null,
                watchdog ? "데몬 종료 보고 - 서버는 정상 (하트비트 계속 수신)" : "데몬 종료 보고");
        processStatus.stopAllNoData(serverId, serverName(serverId), "데몬 종료");
    }

    /**
     * 데몬 재시작 요청 (ADMIN) : 다음 하트비트 응답(restart=Y)으로 감시자가 서버에서 bin/scb_client.sh start 실행.
     * 감시자(클라이언트 1.5~)가 하트비트를 보내고 있고 데몬이 멈춰 있을 때만.
     */
    public AgentView requestRestart(Long serverId, String user) {
        return tx.execute(st -> {
            Agent a = mapper.selectAgent(serverId);
            if (a == null) throw TopologyException.badRequest("에이전트 토큰을 먼저 발급하세요. (server_id=" + serverId + ")");
            if (!a.isWatchdogClient()) {
                throw TopologyException.badRequest("웹 재시작은 하트비트 감시자가 있는 클라이언트 1.5 이상에서만 됩니다. 서버에서 bin/scb_client.sh start 로 시작하세요.");
            }
            if (!Agent.HB_ALIVE.equals(a.heartbeatState(System.currentTimeMillis()))) {
                throw TopologyException.badRequest("하트비트가 끊겨 재시작 명령을 전달할 수 없습니다. 서버 상태를 확인하세요.");
            }
            if (!a.isDaemonDown()) throw TopologyException.conflict("데몬이 실행 중입니다.");
            mapper.updateRestartReq(serverId, "Y", user);
            event(serverId, HeartbeatHist.E_RESTART, a.getDaemonPid(), null, "데몬 재시작 요청 (" + user + ") - 다음 하트비트에 전달");
            return AgentView.of(mapper.selectAgent(serverId), props.getStaleAfterSec());
        });
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
                                "하트비트 " + Agent.HB_MISS_LIMIT + "회 연속 미수신 - 서버 다운 (" + gap + "초 동안 수신 없음, 기준 " + a.heartbeatTimeoutSec() + "초)");
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
