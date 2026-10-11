package com.scb.monitoring.topology.dto;

import java.util.ArrayList;
import java.util.List;

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
    private String status;
    private String sync;
    private Long pairId;
    private String cpu;
    private String mem;
    private String disk;
    private String sessions;
    private List<ProcessView> processes = new ArrayList<>();

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
    public String getSync() { return sync; }
    public Long getPairId() { return pairId; }
    public String getCpu() { return cpu; }
    public String getMem() { return mem; }
    public String getDisk() { return disk; }
    public String getSessions() { return sessions; }
    public List<ProcessView> getProcesses() { return processes; }
}
