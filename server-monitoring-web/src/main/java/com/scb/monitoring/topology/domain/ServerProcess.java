package com.scb.monitoring.topology.domain;

import java.math.BigDecimal;

/** tb_server_process : 서버별 프로세스 */
public class ServerProcess {

    private Long procId;
    private Long serverId;
    private String procNm;
    private String execPath;
    /** 중지 상태면 NULL (화면에는 '-') */
    private Integer pid;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    /** Y / N */
    private String runningYn;
    private Integer sortOrd;

    public boolean isRunning() { return "Y".equals(runningYn); }

    public void setRunning(boolean running) { this.runningYn = running ? "Y" : "N"; }

    public Long getProcId() { return procId; }
    public void setProcId(Long procId) { this.procId = procId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getProcNm() { return procNm; }
    public void setProcNm(String procNm) { this.procNm = procNm; }
    public String getExecPath() { return execPath; }
    public void setExecPath(String execPath) { this.execPath = execPath; }
    public Integer getPid() { return pid; }
    public void setPid(Integer pid) { this.pid = pid; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public String getRunningYn() { return runningYn; }
    public void setRunningYn(String runningYn) { this.runningYn = runningYn; }
    public Integer getSortOrd() { return sortOrd; }
    public void setSortOrd(Integer sortOrd) { this.sortOrd = sortOrd; }
}
