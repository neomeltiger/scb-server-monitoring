package com.scb.monitoring.svcctl.dto;

/** 프로세스 관리 화면 서버 선택 목록 */
public class ServerOption {

    private final Long id;
    private final String systemName;
    private final String ip;
    private final String location;
    private final int serviceCount;

    public ServerOption(Long id, String systemName, String ip, String location, int serviceCount) {
        this.id = id;
        this.systemName = systemName;
        this.ip = ip;
        this.location = location;
        this.serviceCount = serviceCount;
    }

    public Long getId() { return id; }
    public String getSystemName() { return systemName; }
    public String getIp() { return ip; }
    public String getLocation() { return location; }
    public int getServiceCount() { return serviceCount; }
}
