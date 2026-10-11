package com.scb.monitoring.svcctl.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** 서비스(service.list) CPU / MEM 수집 이력 (tb_service_metric_hist) - 메인 홈 서비스 그래프 */
public class ServiceMetricHist {

    private Long svcId;
    private Long serverId;
    private String svcNm;
    private Timestamp collectDt;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    private String runningYn;

    public Long getSvcId() { return svcId; }
    public void setSvcId(Long svcId) { this.svcId = svcId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getSvcNm() { return svcNm; }
    public void setSvcNm(String svcNm) { this.svcNm = svcNm; }
    public Timestamp getCollectDt() { return collectDt; }
    public void setCollectDt(Timestamp collectDt) { this.collectDt = collectDt; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public String getRunningYn() { return runningYn; }
    public void setRunningYn(String runningYn) { this.runningYn = runningYn; }
}
