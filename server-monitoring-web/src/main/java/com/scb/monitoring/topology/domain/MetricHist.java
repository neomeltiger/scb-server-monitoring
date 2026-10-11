package com.scb.monitoring.topology.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** tb_server_metric_hist / tb_process_metric_hist 1건 (procId 는 프로세스 이력일 때만) */
public class MetricHist {

    public static final String SRC_AGENT = "AGENT";
    public static final String SRC_SAMPLE = "SAMPLE";

    private Long serverId;
    private Long procId;
    private Timestamp collectDt;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    private BigDecimal diskPct;
    private Integer sessionCnt;
    private String runningYn;
    private String srcCd;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getProcId() { return procId; }
    public void setProcId(Long procId) { this.procId = procId; }
    public Timestamp getCollectDt() { return collectDt; }
    public void setCollectDt(Timestamp collectDt) { this.collectDt = collectDt; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public BigDecimal getDiskPct() { return diskPct; }
    public void setDiskPct(BigDecimal diskPct) { this.diskPct = diskPct; }
    public Integer getSessionCnt() { return sessionCnt; }
    public void setSessionCnt(Integer sessionCnt) { this.sessionCnt = sessionCnt; }
    public String getRunningYn() { return runningYn; }
    public void setRunningYn(String runningYn) { this.runningYn = runningYn; }
    public String getSrcCd() { return srcCd; }
    public void setSrcCd(String srcCd) { this.srcCd = srcCd; }
}
