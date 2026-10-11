package com.scb.monitoring.topology.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerProcess;

/** 서버 응답 (화면 JS 의 serversData 항목과 같은 형태) */
public class ServerView {

    private Long id;
    private String systemName;
    private String fqdn;
    private String os;
    private String ip;
    /** 설치장소명 (GASAN MTR-A / DRC) */
    private String location;
    private Integer switchNodeId;
    /** 화면 표시 상태 : 서버 정보를 받아오지 못하면 Down (등록 상태는 baseStatus) */
    private String status;
    /** 등록된 상태 (tb_server.status_cd - 서버 수정 화면 기본값) */
    private String baseStatus;
    /** 서버 정보 수신 안 됨 사유 (에이전트 미설치 / 보고 대기 / 보고 끊김 / 수집 중지 / 데몬 종료), 정상이면 null */
    private String noData;
    private String sync;
    private Long pairId;
    private String cpu;
    private String mem;
    private String disk;
    private String sessions;
    private List<ProcessView> processes = new ArrayList<>();

    /** 에이전트 상태 → 서버 정보 수신 안 됨 사유 */
    public static final Map<String, String> NO_DATA_REASON = new HashMap<>();
    static {
        NO_DATA_REASON.put("NONE", "에이전트 미설치");
        NO_DATA_REASON.put("WAITING", "보고 대기");
        NO_DATA_REASON.put("STALE", "보고 끊김");
        NO_DATA_REASON.put("PAUSED", "수집 중지");
        NO_DATA_REASON.put("STOPPED", "데몬 종료");
    }

    /** 에이전트 상태가 정상(OK)이 아니면 사유, 정상이면 null */
    public static String noDataReason(String agentState) {
        return agentState == null || "OK".equals(agentState) ? null : NO_DATA_REASON.getOrDefault(agentState, agentState);
    }

    /**
     * @param agentState 에이전트 상태 (AgentView.state) - OK 가 아니면 서버 정보 수신 안 됨 → 화면 상태 Down
     */
    public static ServerView of(Server s, List<ServerProcess> processes, String agentState) {
        ServerView v = of(s, processes);
        v.noData = noDataReason(agentState);
        if (v.noData != null) v.status = "Down";
        return v;
    }

    public static ServerView of(Server s, List<ServerProcess> processes) {
        ServerView v = new ServerView();
        v.id = s.getServerId();
        v.systemName = s.getSystemNm();
        v.fqdn = s.getFqdn();
        v.os = s.getOsType();
        v.ip = s.getIpAddr();
        v.location = s.getLocationNm();
        v.switchNodeId = s.getSwitchNodeId();
        v.status = s.getStatusCd();
        v.baseStatus = s.getStatusCd();
        v.sync = s.getSyncStatus();
        v.pairId = s.getPairServerId();
        v.cpu = Formats.percent1(s.getCpuPct());
        v.mem = Formats.percent1(s.getMemPct());
        v.disk = Formats.percentPlain(s.getDiskPct());
        v.sessions = Formats.count(s.getSessionCnt());
        if (processes != null) {
            for (ServerProcess p : processes) v.processes.add(ProcessView.of(p));
        }
        return v;
    }

    public Long getId() { return id; }
    public String getSystemName() { return systemName; }
    public String getFqdn() { return fqdn; }
    public String getOs() { return os; }
    public String getIp() { return ip; }
    public String getLocation() { return location; }
    public Integer getSwitchNodeId() { return switchNodeId; }
    public String getStatus() { return status; }
    public String getBaseStatus() { return baseStatus; }
    public String getNoData() { return noData; }
    public String getSync() { return sync; }
    public Long getPairId() { return pairId; }
    public String getCpu() { return cpu; }
    public String getMem() { return mem; }
    public String getDisk() { return disk; }
    public String getSessions() { return sessions; }
    public List<ProcessView> getProcesses() { return processes; }
}
