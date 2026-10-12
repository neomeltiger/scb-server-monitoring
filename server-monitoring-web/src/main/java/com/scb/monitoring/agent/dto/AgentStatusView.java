package com.scb.monitoring.agent.dto;

import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.topology.domain.Server;

/**
 * 서버별 krcasmrx11_client 등록 현황 (메인 홈 "에이전트 미등록 서버" 패널).
 * registered : 토큰 발급 후 정상 보고(OK) 중인 서버만 true
 */
public class AgentStatusView {

    private Long serverId;
    private String systemNm;
    private String ipAddr;
    private String fqdn;
    private String statusCd;
    private String location;
    private boolean registered;
    /** 미등록 사유 (등록 서버는 null) */
    private String reason;
    private AgentView agent;

    public static AgentStatusView of(Server s, Agent a, int staleAfterSec) {
        AgentStatusView v = new AgentStatusView();
        v.serverId = s.getServerId();
        v.systemNm = s.getSystemNm();
        v.ipAddr = s.getIpAddr();
        v.fqdn = s.getFqdn();
        v.statusCd = s.getStatusCd();
        v.location = s.getLocationNm();
        v.agent = AgentView.of(a, staleAfterSec);
        // 서버 정보를 받아오지 못하면(에이전트 상태 OK 아님) 화면 상태 Down
        if (!"OK".equals(v.agent.getState())) v.statusCd = "Down";
        String st = v.agent.getState();
        v.registered = "OK".equals(st);
        if ("NONE".equals(st)) v.reason = "토큰 미발급 (에이전트 미등록)";
        else if ("WAITING".equals(st)) v.reason = "토큰 발급 후 보고 없음 (클라이언트 미설치/미기동)";
        else if ("STALE".equals(st)) v.reason = "보고 끊김 (데몬 중단 또는 네트워크 단절)";
        else if ("LOST".equals(st)) v.reason = "하트비트 끊김 (서버 다운 또는 데몬 비정상 종료)";
        else if ("PAUSED".equals(st)) v.reason = "수집 중지 (웹 에이전트 관리에서 중지함)";
        else if ("STOPPED".equals(st)) v.reason = "데몬 종료 (웹에서 종료함 - 서버에서 다시 시작 필요)";
        return v;
    }

    public Long getServerId() { return serverId; }
    public String getSystemNm() { return systemNm; }
    public String getIpAddr() { return ipAddr; }
    public String getFqdn() { return fqdn; }
    public String getStatusCd() { return statusCd; }
    public String getLocation() { return location; }
    public boolean isRegistered() { return registered; }
    public String getReason() { return reason; }
    public AgentView getAgent() { return agent; }
}
