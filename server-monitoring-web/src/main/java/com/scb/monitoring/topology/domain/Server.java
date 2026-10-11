package com.scb.monitoring.topology.domain;

import java.math.BigDecimal;

/**
 * tb_server (+ 조회 시 tb_net_node.location_nm, tb_server_metric 값을 함께 담음)
 */
public class Server {

    public static final String STATUS_ACTIVE = "Active";
    public static final String STATUS_STANDBY = "Standby";
    public static final String STATUS_DOWN = "Down";

    private Long serverId;
    private String systemNm;
    private String fqdn;
    private String osType;
    private String ipAddr;
    private Integer switchNodeId;
    private String statusCd;
    private String syncStatus;
    private Long pairServerId;
    private Integer sortOrd;

    // 조회 전용 (JOIN)
    private String locationNm;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    private BigDecimal diskPct;
    private Integer sessionCnt;

    public boolean isDown() { return STATUS_DOWN.equals(statusCd); }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getSystemNm() { return systemNm; }
    public void setSystemNm(String systemNm) { this.systemNm = systemNm; }
    public String getFqdn() { return fqdn; }
    public void setFqdn(String fqdn) { this.fqdn = fqdn; }
    public String getOsType() { return osType; }
    public void setOsType(String osType) { this.osType = osType; }
    public String getIpAddr() { return ipAddr; }
    public void setIpAddr(String ipAddr) { this.ipAddr = ipAddr; }
    public Integer getSwitchNodeId() { return switchNodeId; }
    public void setSwitchNodeId(Integer switchNodeId) { this.switchNodeId = switchNodeId; }
    public String getStatusCd() { return statusCd; }
    public void setStatusCd(String statusCd) { this.statusCd = statusCd; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    public Long getPairServerId() { return pairServerId; }
    public void setPairServerId(Long pairServerId) { this.pairServerId = pairServerId; }
    public Integer getSortOrd() { return sortOrd; }
    public void setSortOrd(Integer sortOrd) { this.sortOrd = sortOrd; }
    public String getLocationNm() { return locationNm; }
    public void setLocationNm(String locationNm) { this.locationNm = locationNm; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public BigDecimal getDiskPct() { return diskPct; }
    public void setDiskPct(BigDecimal diskPct) { this.diskPct = diskPct; }
    public Integer getSessionCnt() { return sessionCnt; }
    public void setSessionCnt(Integer sessionCnt) { this.sessionCnt = sessionCnt; }
}
