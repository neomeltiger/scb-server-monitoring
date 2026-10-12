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
     * LOST: 하트비트 끊김 (서버 다운 또는 데몬 비정상 종료 - 감시 프로세스는 종료로 표시)
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

    public static AgentView of(Agent a, int staleAfterSec) {
        AgentView v = new AgentView();
        if (a == null) {
            v.state = "NONE";
            v.daemonState = "NONE";
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

        // 데몬 상태
        if (a.getLastPollDt() == null) {
            v.daemonState = "NONE";
        } else {
            v.lastPollAgoSec = Math.max(0, (now - a.getLastPollDt().getTime()) / 1000);
            String d = a.getDaemonState();
            if (Agent.D_STOPPED.equals(d)) v.daemonState = Agent.D_STOPPED;
            else if (v.lastPollAgoSec > Agent.POLL_TIMEOUT_SEC) v.daemonState = "NO_RESPONSE";
            else v.daemonState = d != null ? d : Agent.D_RUNNING;   // 데몬 정보가 없는 이전 클라이언트
        }

        // 수집 상태
        if (a.getLastReportDt() != null) {
            v.lastReportAgoSec = Math.max(0, (now - a.getLastReportDt().getTime()) / 1000);
        }
        if (Agent.D_STOPPED.equals(a.getDaemonState()) || Agent.D_STOPPING.equals(a.getDaemonState())) {
            v.state = "STOPPED";
        } else if (Agent.HB_LOST.equals(v.hbState)) {
            v.state = "LOST";
        } else if (!a.isCollectOn()) {
            v.state = "PAUSED";
        } else if (a.getLastReportDt() == null) {
            v.state = "WAITING";
        } else {
            v.state = v.lastReportAgoSec > v.staleLimitSec ? "STALE" : "OK";
        }
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
}
