package com.scb.monitoring.topology.dto;

import com.scb.monitoring.topology.domain.ServerProcess;

/** 프로세스 응답 (화면 JS 의 process 객체와 같은 형태) */
public class ProcessView {

    private Long id;
    private String name;
    private String path;
    /** 중지 상태면 "-" */
    private String pid;
    private String cpu;
    private String mem;
    private boolean running;

    public static ProcessView of(ServerProcess p) {
        ProcessView v = new ProcessView();
        v.id = p.getProcId();
        v.name = p.getProcNm();
        v.path = p.getExecPath();
        v.pid = p.getPid() == null ? "-" : String.valueOf(p.getPid());
        v.cpu = Formats.percent1(p.getCpuPct());
        v.mem = Formats.percent1(p.getMemPct());
        v.running = p.isRunning();
        return v;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }
    public String getPid() { return pid; }
    public String getCpu() { return cpu; }
    public String getMem() { return mem; }
    public boolean isRunning() { return running; }
}
