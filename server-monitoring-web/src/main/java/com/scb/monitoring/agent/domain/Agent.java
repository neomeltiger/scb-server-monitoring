package com.scb.monitoring.agent.domain;

import java.sql.Timestamp;

/** tb_agent : 서버 에이전트(svr_collect_client) 토큰, 마지막 보고, 데몬 제어/상태 */
public class Agent {

    /** 웹에서 고를 수 있는 수집 주기 (초) */
    public static final int[] COLLECT_INTERVALS = {10, 30, 60, 120, 300, 600};
    public static final int DEFAULT_COLLECT_INTERVAL = 60;
    /** 데몬이 웹을 확인(heartbeat)하는 주기 (초) - 클라이언트 scb_client.sh 의 POLL_SEC 와 같게 */
    public static final int POLL_SEC = 10;
    /** 이 시간 이상 확인이 없으면 "응답 없음" (cron 방식 1.1 클라이언트의 1분 주기도 포함) */
    public static final int POLL_TIMEOUT_SEC = 90;

    /** 네트워크 구성도에서 고를 수 있는 하트비트 주기 (초) */
    public static final int[] HB_INTERVALS = {3, 5, 10, 15, 30, 60};
    public static final int DEFAULT_HB_INTERVAL = 10;
    /** 하트비트 셸이 없는 이전 데몬(1.2 / 1.3)은 웹 확인(10초 주기)으로 판단 - 수집 중 지연을 감안한 끊김 기준 (초) */
    public static final int LEGACY_HB_TIMEOUT_SEC = 45;
    /** 하트비트를 이 횟수만큼 연속으로 못 받으면 서버 다운 */
    public static final int HB_MISS_LIMIT = 3;
    /** 끊김 기준 여유 (초) : 네트워크 지연 */
    public static final int HB_GRACE_SEC = 2;

    /** 하트비트 감시자(1.5~)가 알린 데몬 상태 */
    public static final String HD_RUNNING = "RUNNING";
    public static final String HD_PAUSED = "PAUSED";
    public static final String HD_STOPPED = "STOPPED";
    public static final String HD_DEAD = "DEAD";

    /** 하트비트 상태 : NONE(하트비트 없음 - 미설치/cron 클라이언트) / ALIVE / LOST(끊김) / STOPPED(데몬 종료) */
    public static final String HB_NONE = "NONE";
    public static final String HB_ALIVE = "ALIVE";
    public static final String HB_LOST = "LOST";
    public static final String HB_STOPPED = "STOPPED";

    public static final String D_RUNNING = "RUNNING";
    public static final String D_PAUSED = "PAUSED";
    public static final String D_STOPPING = "STOPPING";
    public static final String D_STOPPED = "STOPPED";

    private Long serverId;
    private String tokenHash;
    private Timestamp issueDt;
    private String issueUser;
    private Timestamp lastReportDt;
    private String lastReportIp;
    private String agentHost;
    private String agentOs;
    private String agentVer;
    private String peerStatus;
    private String collectYn = "Y";
    private int collectIntervalSec = DEFAULT_COLLECT_INTERVAL;
    private String shutdownReqYn = "N";
    private String daemonState;
    private Integer daemonPid;
    private Timestamp daemonStartDt;
    private Timestamp lastPollDt;
    private String ctlUser;
    private Timestamp ctlDt;
    private int hbIntervalSec = DEFAULT_HB_INTERVAL;
    private String hbState;
    private Timestamp lastHbDt;
    private Integer hbPid;
    private String hbDaemonState;
    private String restartReqYn = "N";
    private String restartReqUser;
    private Timestamp restartReqDt;
    private String bootAutoYn;
    private String bootTime;

    public static boolean isAllowedInterval(int sec) {
        for (int i : COLLECT_INTERVALS) if (i == sec) return true;
        return false;
    }

    public static boolean isAllowedHbInterval(int sec) {
        for (int i : HB_INTERVALS) if (i == sec) return true;
        return false;
    }

    // ---- 하트비트 판정 (화면 상태와 감시가 같은 기준을 씀)

    /** 하트비트 셸(클라이언트 1.4~)이 보낸 적이 있음 */
    public boolean isHeartbeatClient() { return lastHbDt != null; }

    /** 판정에 쓰는 마지막 생존 신호 : 하트비트, 없으면 데몬 웹 확인(1.2 / 1.3), cron 클라이언트·미설치는 null */
    public Timestamp lastBeatDt() {
        if (lastHbDt != null) return lastHbDt;
        return daemonState != null ? lastPollDt : null;
    }

    /** 하트비트 감시자(클라이언트 1.5~) : 데몬과 따로 돌며 데몬 상태를 함께 알림 → 서버 다운과 데몬 문제를 구분 */
    public boolean isWatchdogClient() { return hbDaemonState != null; }

    /** 끊김(서버 다운) 기준 (초) : 하트비트 3회 연속 미수신 = 주기 x 3 + 2초, 이전 데몬은 45초 */
    public int heartbeatTimeoutSec() {
        return isHeartbeatClient() ? hbIntervalSec * HB_MISS_LIMIT + HB_GRACE_SEC : LEGACY_HB_TIMEOUT_SEC;
    }

    /** 연속 미수신 횟수 (마지막 수신 후 주기가 몇 번 지났는지) */
    public Integer heartbeatMisses(long now) {
        Long ago = heartbeatAgoSec(now);
        if (ago == null) return null;
        int interval = isHeartbeatClient() ? hbIntervalSec : POLL_SEC;
        return (int) Math.max(0, (ago - HB_GRACE_SEC) / interval);
    }

    /** 데몬이 멈춤 : 감시자가 알린 상태(DEAD / STOPPED), 이전 클라이언트는 데몬의 종료 보고 */
    public boolean isDaemonDown() {
        if (isWatchdogClient()) return HD_DEAD.equals(hbDaemonState) || HD_STOPPED.equals(hbDaemonState);
        return D_STOPPED.equals(daemonState) || D_STOPPING.equals(daemonState);
    }

    /** 데몬 비정상 종료 (감시자가 PID 파일은 있는데 프로세스가 없음을 알림) */
    public boolean isDaemonDead() { return HD_DEAD.equals(hbDaemonState); }

    /** 관리자가 일부러 멈춤 (웹 [종료] / 서버 scb_client.sh stop) - 데몬 중단 알람 대상 아님 */
    public boolean isDaemonStopIntended() {
        return D_STOPPED.equals(daemonState) || D_STOPPING.equals(daemonState) || isShutdownRequested();
    }

    public boolean isRestartPending() { return "Y".equals(restartReqYn) || "S".equals(restartReqYn); }

    public Long heartbeatAgoSec(long now) {
        Timestamp t = lastBeatDt();
        return t == null ? null : Math.max(0, (now - t.getTime()) / 1000);
    }

    /** NONE / ALIVE / LOST / STOPPED */
    public String heartbeatState(long now) {
        Long ago = heartbeatAgoSec(now);
        if (ago == null) return HB_NONE;
        // 감시자 없는 클라이언트는 데몬이 멈추면 생존 신호도 멈춤 → 종료 보고를 받았으면 서버 다운이 아님
        if (!isWatchdogClient() && (D_STOPPED.equals(daemonState) || D_STOPPING.equals(daemonState))) return HB_STOPPED;
        // 연속 미수신 횟수(heartbeatMisses)가 3 이 되는 순간과 같게 (>=)
        return ago >= heartbeatTimeoutSec() ? HB_LOST : HB_ALIVE;
    }

    public boolean isCollectOn() { return !"N".equals(collectYn); }
    public boolean isShutdownRequested() { return "Y".equals(shutdownReqYn); }
    /** 관리자가 일부러 멈춘 상태 (수집 중지 / 종료) - 보고 끊김 알람 대상 아님 */
    public boolean isIntentionallyIdle() {
        return !isCollectOn() || D_STOPPED.equals(daemonState) || D_STOPPING.equals(daemonState) || isShutdownRequested();
    }
    /** 보고 끊김 기준 (초) : 설정값과 수집 주기 3배 중 큰 값 */
    public int staleLimitSec(int staleAfterSec) {
        return Math.max(staleAfterSec, collectIntervalSec * 3);
    }

    public String getCollectYn() { return collectYn; }
    public void setCollectYn(String collectYn) { this.collectYn = collectYn; }
    public int getCollectIntervalSec() { return collectIntervalSec; }
    public void setCollectIntervalSec(int collectIntervalSec) { this.collectIntervalSec = collectIntervalSec; }
    public String getShutdownReqYn() { return shutdownReqYn; }
    public void setShutdownReqYn(String shutdownReqYn) { this.shutdownReqYn = shutdownReqYn; }
    public String getDaemonState() { return daemonState; }
    public void setDaemonState(String daemonState) { this.daemonState = daemonState; }
    public Integer getDaemonPid() { return daemonPid; }
    public void setDaemonPid(Integer daemonPid) { this.daemonPid = daemonPid; }
    public Timestamp getDaemonStartDt() { return daemonStartDt; }
    public void setDaemonStartDt(Timestamp daemonStartDt) { this.daemonStartDt = daemonStartDt; }
    public Timestamp getLastPollDt() { return lastPollDt; }
    public void setLastPollDt(Timestamp lastPollDt) { this.lastPollDt = lastPollDt; }
    public String getCtlUser() { return ctlUser; }
    public void setCtlUser(String ctlUser) { this.ctlUser = ctlUser; }
    public Timestamp getCtlDt() { return ctlDt; }
    public void setCtlDt(Timestamp ctlDt) { this.ctlDt = ctlDt; }

    public int getHbIntervalSec() { return hbIntervalSec; }
    public void setHbIntervalSec(int hbIntervalSec) { this.hbIntervalSec = hbIntervalSec; }
    public String getHbState() { return hbState; }
    public void setHbState(String hbState) { this.hbState = hbState; }
    public Timestamp getLastHbDt() { return lastHbDt; }
    public void setLastHbDt(Timestamp lastHbDt) { this.lastHbDt = lastHbDt; }
    public Integer getHbPid() { return hbPid; }
    public void setHbPid(Integer hbPid) { this.hbPid = hbPid; }

    public String getHbDaemonState() { return hbDaemonState; }
    public void setHbDaemonState(String hbDaemonState) { this.hbDaemonState = hbDaemonState; }
    public String getRestartReqYn() { return restartReqYn; }
    public void setRestartReqYn(String restartReqYn) { this.restartReqYn = restartReqYn; }
    public String getRestartReqUser() { return restartReqUser; }
    public void setRestartReqUser(String restartReqUser) { this.restartReqUser = restartReqUser; }
    public Timestamp getRestartReqDt() { return restartReqDt; }
    public void setRestartReqDt(Timestamp restartReqDt) { this.restartReqDt = restartReqDt; }

    public String getBootAutoYn() { return bootAutoYn; }
    public void setBootAutoYn(String bootAutoYn) { this.bootAutoYn = bootAutoYn; }
    public String getBootTime() { return bootTime; }
    public void setBootTime(String bootTime) { this.bootTime = bootTime; }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Timestamp getIssueDt() { return issueDt; }
    public void setIssueDt(Timestamp issueDt) { this.issueDt = issueDt; }
    public String getIssueUser() { return issueUser; }
    public void setIssueUser(String issueUser) { this.issueUser = issueUser; }
    public Timestamp getLastReportDt() { return lastReportDt; }
    public void setLastReportDt(Timestamp lastReportDt) { this.lastReportDt = lastReportDt; }
    public String getLastReportIp() { return lastReportIp; }
    public void setLastReportIp(String lastReportIp) { this.lastReportIp = lastReportIp; }
    public String getAgentHost() { return agentHost; }
    public void setAgentHost(String agentHost) { this.agentHost = agentHost; }
    public String getAgentOs() { return agentOs; }
    public void setAgentOs(String agentOs) { this.agentOs = agentOs; }
    public String getAgentVer() { return agentVer; }
    public void setAgentVer(String agentVer) { this.agentVer = agentVer; }
    public String getPeerStatus() { return peerStatus; }
    public void setPeerStatus(String peerStatus) { this.peerStatus = peerStatus; }
}
