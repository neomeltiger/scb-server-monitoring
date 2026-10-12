package com.scb.monitoring.agent.dto;

import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.topology.domain.Server;

/**
 * 하트비트 모니터링 화면 / 화면 즉시 갱신 (GET /api/agents/heartbeat) 한 줄.
 * 화면은 몇 초마다 이 목록을 받아 에이전트 상태(agentState)가 바뀐 서버가 있으면 바로 다시 그립니다.
 */
public class HeartbeatView {

    private Long serverId;
    private String systemNm;
    private String ipAddr;
    private String location;
    /** 등록 상태 (tb_server.status_cd) */
    private String statusCd;
    /** 에이전트 상태 (AgentView.state : NONE / WAITING / OK / STALE / LOST / PAUSED / STOPPED) */
    private String agentState;
    /** 하트비트 상태 : NONE / ALIVE / LOST / STOPPED */
    private String hbState;
    /** true = 하트비트 셸(1.4~), false = 데몬 웹 확인으로 판단(1.2 / 1.3) 또는 하트비트 없음 */
    private boolean hbClient;
    private int hbIntervalSec;
    private int hbTimeoutSec;
    private String lastHbDt;
    private Long lastHbAgoSec;
    private Integer hbPid;
    private String agentVer;
    private String daemonState;

    public static HeartbeatView of(Server s, Agent a, int staleAfterSec) {
        HeartbeatView v = new HeartbeatView();
        v.serverId = s.getServerId();
        v.systemNm = s.getSystemNm();
        v.ipAddr = s.getIpAddr();
        v.location = s.getLocationNm();
        v.statusCd = s.getStatusCd();
        AgentView av = AgentView.of(a, staleAfterSec);
        v.agentState = av.getState();
        v.hbState = av.getHbState();
        v.hbClient = av.isHbClient();
        v.hbIntervalSec = av.getHbIntervalSec();
        v.hbTimeoutSec = a == null ? Agent.DEFAULT_HB_INTERVAL * 2 + 3 : av.getHbTimeoutSec();
        v.lastHbDt = av.getLastHbDt();
        v.lastHbAgoSec = av.getLastHbAgoSec();
        v.hbPid = av.getHbPid();
        v.agentVer = av.getAgentVer();
        v.daemonState = av.getDaemonState();
        return v;
    }

    public Long getServerId() { return serverId; }
    public String getSystemNm() { return systemNm; }
    public String getIpAddr() { return ipAddr; }
    public String getLocation() { return location; }
    public String getStatusCd() { return statusCd; }
    public String getAgentState() { return agentState; }
    public String getHbState() { return hbState; }
    public boolean isHbClient() { return hbClient; }
    public int getHbIntervalSec() { return hbIntervalSec; }
    public int getHbTimeoutSec() { return hbTimeoutSec; }
    public String getLastHbDt() { return lastHbDt; }
    public Long getLastHbAgoSec() { return lastHbAgoSec; }
    public Integer getHbPid() { return hbPid; }
    public String getAgentVer() { return agentVer; }
    public String getDaemonState() { return daemonState; }
}
