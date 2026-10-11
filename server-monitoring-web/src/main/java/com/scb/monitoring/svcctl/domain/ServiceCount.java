package com.scb.monitoring.svcctl.domain;

/** 서버별 서비스 수 (프로세스 관리 화면 서버 선택 목록) */
public class ServiceCount {

    private Long serverId;
    private Integer svcCnt;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Integer getSvcCnt() { return svcCnt; }
    public void setSvcCnt(Integer svcCnt) { this.svcCnt = svcCnt; }
}
