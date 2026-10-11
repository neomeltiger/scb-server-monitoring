package com.scb.monitoring.topology.domain;

import java.math.BigDecimal;

/** tb_server_metric : 서버 자원 현황 (서버당 1건) */
public class ServerMetric {

    private Long serverId;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    /** NULL = N/A (서버 Down) */
    private BigDecimal diskPct;
    private Integer sessionCnt;

    public ServerMetric() { }

    public ServerMetric(Long serverId, String cpu, String mem, String disk, int sessions) {
        this.serverId = serverId;
        this.cpuPct = new BigDecimal(cpu);
        this.memPct = new BigDecimal(mem);
        this.diskPct = disk == null ? null : new BigDecimal(disk);
        this.sessionCnt = sessions;
    }

    /** 신규 / 복구된 서버의 기본 자원 값 (기존 화면과 동일) */
    public static ServerMetric initial(Long serverId) {
        return new ServerMetric(serverId, "10.5", "45.0", "40.0", 120);
    }

    /** Down 서버 : 0%, 디스크 N/A, 세션 0 */
    public static ServerMetric down(Long serverId) {
        return new ServerMetric(serverId, "0.0", "0.0", null, 0);
    }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public BigDecimal getDiskPct() { return diskPct; }
    public void setDiskPct(BigDecimal diskPct) { this.diskPct = diskPct; }
    public Integer getSessionCnt() { return sessionCnt; }
    public void setSessionCnt(Integer sessionCnt) { this.sessionCnt = sessionCnt; }
}
