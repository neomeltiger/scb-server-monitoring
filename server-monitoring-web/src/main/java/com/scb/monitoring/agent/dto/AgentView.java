package com.scb.monitoring.agent.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import com.scb.monitoring.agent.domain.Agent;

/** 에이전트 상태 응답 (네트워크 구성도 상세 / 프로세스 관리 / 에이전트 관리 화면) */
public class AgentView {

    /**
     * 수집(보고) 상태
     * NONE: 토큰 미발급 / STOPPED: 데몬 종료(웹에서 종료) / PAUSED: 수집 중지(웹에서 중지)
     * WAITING: 발급 후 보고 없음 / OK: 정상 보고 / STALE: 보고 끊김
     * LOST: 하트비트 3회 연속 끊김 = 서버 다운 (감시 프로세스는 종료, 서버 Down 표시)
     * STOPPED 는 데몬만 멈춘 상태 (웹 [종료] / 서버 stop / 비정상 종료) - 하트비트가 오면 서버는 정상, 재시작 알림
     */
    private String state;
    /** 데몬 상태 : NONE(확인 기록 없음) / RUNNING / PAUSED / STOPPING / STOPPED / NO_RESPONSE */
    private String daemonState;
    private String issueDt;
    private String issueUser;
    private String lastReportDt;
    private Long lastReportAgoSec;
    private String lastReportIp;
    private String agentHost;
    private String agentOs;
    private String agentVer;
    private String peerStatus;
    // 웹 설정
    private boolean collectOn = true;
    private int collectIntervalSec = Agent.DEFAULT_COLLECT_INTERVAL;
    /** 실제 적용 수집 주기 - 서버 부하(CPU/메모리 기준 초과) 시 늘어남 (agent.service.LoadThrottle) */
    private int effectiveIntervalSec = Agent.DEFAULT_COLLECT_INTERVAL;
    private boolean loadThrottled;
    private String loadReason;
    private boolean shutdownPending;
    private String ctlUser;
    private String ctlDt;
    // 데몬 보고
    private Integer daemonPid;
    private String daemonStartDt;
    private String lastPollDt;
    private Long lastPollAgoSec;
    private int staleLimitSec;
    // 하트비트
    /** NONE / ALIVE / LOST / STOPPED (Agent.heartbeatState) */
    private String hbState = Agent.HB_NONE;
    /** 하트비트 셸(클라이언트 1.4~) 사용 여부 - false 면 데몬 웹 확인으로 판단 */
    private boolean hbClient;
    private int hbIntervalSec = Agent.DEFAULT_HB_INTERVAL;
    private int hbTimeoutSec;
    private String lastHbDt;
    private Long lastHbAgoSec;
    private Integer hbPid;
    /** 연속 미수신 횟수 / 서버 다운 기준 횟수(3) */
    private Integer hbMisses;
    private int hbMissLimit = Agent.HB_MISS_LIMIT;
    /** 서버 다운으로 볼지 (하트비트 끊김, 또는 생존 신호 자체가 없음) - 데몬만 문제면 false */
    private boolean serverDown;
    /** 데몬 문제 알림 (서버는 정상) : 데몬 종료 / 데몬 비정상 종료 / 데몬 응답 없음 - 없으면 null */
    private String daemonIssue;
    /** 웹에서 데몬 재시작 가능 (하트비트 감시자 1.5~, 하트비트 수신 중) */
    private boolean canRestart;
    private boolean restartPending;
    private String restartReqUser;
    private String restartReqDt;
    private boolean watchdog;
    /** 부팅 자동 시작 설치 : Y / N / null(알 수 없음 - 클라이언트 1.6 미만) */
    private String bootAuto;
    private String bootTime;

    public static AgentView of(Agent a, int staleAfterSec) {
        AgentView v = new AgentView();
        if (a == null) {
            v.state = "NONE";
            v.daemonState = "NONE";
            v.serverDown = true;
            v.staleLimitSec = staleAfterSec;
            return v;
        }
        long now = System.currentTimeMillis();
        v.issueDt = fmt(a.getIssueDt());
        v.issueUser = a.getIssueUser();
        v.lastReportDt = fmt(a.getLastReportDt());
        v.lastReportIp = a.getLastReportIp();
        v.agentHost = a.getAgentHost();
        v.agentOs = a.getAgentOs();
        v.agentVer = a.getAgentVer();
        v.peerStatus = a.getPeerStatus();
        v.collectOn = a.isCollectOn();
        v.collectIntervalSec = a.getCollectIntervalSec();
        v.effectiveIntervalSec = a.getCollectIntervalSec();
        v.shutdownPending = a.isShutdownRequested();
        v.ctlUser = a.getCtlUser();
        v.ctlDt = fmt(a.getCtlDt());
        v.daemonPid = a.getDaemonPid();
        v.daemonStartDt = fmt(a.getDaemonStartDt());
        v.lastPollDt = fmt(a.getLastPollDt());
        v.staleLimitSec = a.staleLimitSec(staleAfterSec);
        v.hbState = a.heartbeatState(now);
        v.hbClient = a.isHeartbeatClient();
        v.hbIntervalSec = a.getHbIntervalSec();
        v.hbTimeoutSec = a.heartbeatTimeoutSec();
        v.lastHbDt = fmt(a.lastBeatDt());
        v.lastHbAgoSec = a.heartbeatAgoSec(now);
        v.hbPid = a.isHeartbeatClient() ? a.getHbPid() : a.getDaemonPid();
        v.hbMisses = a.heartbeatMisses(now);
        v.watchdog = a.isWatchdogClient();
        v.restartPending = a.isRestartPending();
        v.restartReqUser = a.getRestartReqUser();
        v.restartReqDt = fmt(a.getRestartReqDt());
        v.bootAuto = a.getBootAutoYn();
        v.bootTime = a.getBootTime();

        // 데몬 상태
        if (a.getLastPollDt() == null) {
            v.daemonState = "NONE";
        } else {
            v.lastPollAgoSec = Math.max(0, (now - a.getLastPollDt().getTime()) / 1000);
            String d = a.getDaemonState();
            if (a.isDaemonDead()) v.daemonState = Agent.HD_DEAD;
            else if (a.isWatchdogClient() && Agent.HD_STOPPED.equals(a.getHbDaemonState())) v.daemonState = Agent.D_STOPPED;
            else if (Agent.D_STOPPED.equals(d)) v.daemonState = Agent.D_STOPPED;
            else if (v.lastPollAgoSec > Agent.POLL_TIMEOUT_SEC) v.daemonState = "NO_RESPONSE";
            else v.daemonState = d != null ? d : Agent.D_RUNNING;   // 데몬 정보가 없는 이전 클라이언트
        }

        // 수집 상태
        if (a.getLastReportDt() != null) {
            v.lastReportAgoSec = Math.max(0, (now - a.getLastReportDt().getTime()) / 1000);
        }
        if (Agent.HB_LOST.equals(v.hbState)) {
            v.state = "LOST";
        } else if (a.isDaemonDown()) {
            v.state = "STOPPED";
        } else if (!a.isCollectOn()) {
            v.state = "PAUSED";
        } else if (a.getLastReportDt() == null) {
            v.state = "WAITING";
        } else {
            v.state = v.lastReportAgoSec > v.staleLimitSec ? "STALE" : "OK";
        }

        // 서버 다운 / 데몬 문제 구분 : 생존 신호(하트비트)가 오면 서버는 정상
        boolean alive = Agent.HB_ALIVE.equals(v.hbState) || Agent.HB_STOPPED.equals(v.hbState);
        v.serverDown = !"OK".equals(v.state) && !alive;
        if (alive && "STOPPED".equals(v.state)) {
            v.daemonIssue = a.isDaemonDead() ? "데몬 비정상 종료" : "데몬 종료";
        } else if (Agent.HB_ALIVE.equals(v.hbState) && "STALE".equals(v.state)) {
            v.daemonIssue = "데몬 응답 없음 (보고 끊김)";
        }
        v.canRestart = a.isWatchdogClient() && Agent.HB_ALIVE.equals(v.hbState);
        return v;
    }

    static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    public String getState() { return state; }
    public String getDaemonState() { return daemonState; }
    public String getIssueDt() { return issueDt; }
    public String getIssueUser() { return issueUser; }
    public String getLastReportDt() { return lastReportDt; }
    public Long getLastReportAgoSec() { return lastReportAgoSec; }
    public String getLastReportIp() { return lastReportIp; }
    public String getAgentHost() { return agentHost; }
    public String getAgentOs() { return agentOs; }
    public String getAgentVer() { return agentVer; }
    public String getPeerStatus() { return peerStatus; }
    public boolean isCollectOn() { return collectOn; }
    public int getCollectIntervalSec() { return collectIntervalSec; }
    public int getEffectiveIntervalSec() { return effectiveIntervalSec; }
    public boolean isLoadThrottled() { return loadThrottled; }
    public String getLoadReason() { return loadReason; }

    /** 서버의 마지막 자원값으로 부하 시 수집 주기 연장 여부를 채움 */
    public AgentView applyLoad(com.scb.monitoring.topology.domain.Server s, com.scb.monitoring.agent.AgentProperties p) {
        if (s == null || "NONE".equals(state)) return this;   // 에이전트 없는 서버는 해당 없음
        effectiveIntervalSec = com.scb.monitoring.agent.service.LoadThrottle.effectiveInterval(collectIntervalSec, s.getCpuPct(), s.getMemPct(), p);
        loadReason = com.scb.monitoring.agent.service.LoadThrottle.reason(s.getCpuPct(), s.getMemPct(), p);
        loadThrottled = loadReason != null;
        return this;
    }
    public boolean isShutdownPending() { return shutdownPending; }
    public String getCtlUser() { return ctlUser; }
    public String getCtlDt() { return ctlDt; }
    public Integer getDaemonPid() { return daemonPid; }
    public String getDaemonStartDt() { return daemonStartDt; }
    public String getLastPollDt() { return lastPollDt; }
    public Long getLastPollAgoSec() { return lastPollAgoSec; }
    public int getStaleLimitSec() { return staleLimitSec; }
    public int[] getCollectIntervals() { return Agent.COLLECT_INTERVALS; }
    public String getHbState() { return hbState; }
    public boolean isHbClient() { return hbClient; }
    public int getHbIntervalSec() { return hbIntervalSec; }
    public int getHbTimeoutSec() { return hbTimeoutSec; }
    public String getLastHbDt() { return lastHbDt; }
    public Long getLastHbAgoSec() { return lastHbAgoSec; }
    public Integer getHbPid() { return hbPid; }
    public int[] getHbIntervals() { return Agent.HB_INTERVALS; }
    public Integer getHbMisses() { return hbMisses; }
    public int getHbMissLimit() { return hbMissLimit; }
    public boolean isServerDown() { return serverDown; }
    public String getDaemonIssue() { return daemonIssue; }
    public boolean isCanRestart() { return canRestart; }
    public boolean isRestartPending() { return restartPending; }
    public String getRestartReqUser() { return restartReqUser; }
    public String getRestartReqDt() { return restartReqDt; }
    public boolean isWatchdog() { return watchdog; }
    public String getBootAuto() { return bootAuto; }
    public String getBootTime() { return bootTime; }
}
