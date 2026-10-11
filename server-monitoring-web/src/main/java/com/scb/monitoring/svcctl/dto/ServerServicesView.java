package com.scb.monitoring.svcctl.dto;

import java.util.List;

import com.scb.monitoring.agent.dto.AgentView;

/** 프로세스 관리 화면 : 선택 서버의 서비스 목록 + 에이전트 상태 + 최근 제어 명령 */
public class ServerServicesView {

    private Long serverId;
    private String systemName;
    private String ip;
    private String status;
    private AgentView agent;
    private List<ServiceView> services;
    private List<CommandView> commands;
    /** 처리 중(REQUESTED/SENT) 명령 수 - 화면이 자동 새로고침 여부 판단 */
    private int activeCommands;

    public ServerServicesView(Long serverId, String systemName, String ip, String status, AgentView agent,
                              List<ServiceView> services, List<CommandView> commands, int activeCommands) {
        this.serverId = serverId;
        this.systemName = systemName;
        this.ip = ip;
        this.status = status;
        this.agent = agent;
        this.services = services;
        this.commands = commands;
        this.activeCommands = activeCommands;
    }

    public Long getServerId() { return serverId; }
    public String getSystemName() { return systemName; }
    public String getIp() { return ip; }
    public String getStatus() { return status; }
    public AgentView getAgent() { return agent; }
    public List<ServiceView> getServices() { return services; }
    public List<CommandView> getCommands() { return commands; }
    public int getActiveCommands() { return activeCommands; }
}
