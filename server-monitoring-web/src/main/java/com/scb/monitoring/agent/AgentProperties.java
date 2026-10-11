package com.scb.monitoring.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 app.agent.* (서버 에이전트 krcasmrx11_client 연동) */
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /** 에이전트 보고 주기 (초) - 에이전트 설정(config)으로 전달 */
    private int reportIntervalSec = 60;
    /** 수집 이력 보관 일수 */
    private int historyKeepDays = 7;
    /** 에이전트가 가져가지 않은 명령(REQUESTED) 만료 (분) */
    private int commandRequestTimeoutMin = 10;
    /** 결과가 오지 않은 명령(SENT) 만료 (분) */
    private int commandResultTimeoutMin = 30;
    /** 에이전트 보고 최대 크기 (바이트) */
    private int maxReportBytes = 512 * 1024;
    /** "보고 없음" 으로 표시할 기준 (초) */
    private int staleAfterSec = 300;
    /**
     * 서버(에이전트)가 접속할 웹 주소 (client.env 의 SCB_WEB_URL). 예: http://10.0.1.20:8080/server-monitoring-web
     * 비우면 화면을 연 주소를 쓰되, localhost 로 열었으면 이 PC 의 네트워크 IP 로 바꿔 줌
     */
    private String publicUrl = "";

    public int getReportIntervalSec() { return reportIntervalSec; }
    public void setReportIntervalSec(int reportIntervalSec) { this.reportIntervalSec = reportIntervalSec; }
    public int getHistoryKeepDays() { return historyKeepDays; }
    public void setHistoryKeepDays(int historyKeepDays) { this.historyKeepDays = historyKeepDays; }
    public int getCommandRequestTimeoutMin() { return commandRequestTimeoutMin; }
    public void setCommandRequestTimeoutMin(int commandRequestTimeoutMin) { this.commandRequestTimeoutMin = commandRequestTimeoutMin; }
    public int getCommandResultTimeoutMin() { return commandResultTimeoutMin; }
    public void setCommandResultTimeoutMin(int commandResultTimeoutMin) { this.commandResultTimeoutMin = commandResultTimeoutMin; }
    public int getMaxReportBytes() { return maxReportBytes; }
    public void setMaxReportBytes(int maxReportBytes) { this.maxReportBytes = maxReportBytes; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public int getStaleAfterSec() { return staleAfterSec; }
    public void setStaleAfterSec(int staleAfterSec) { this.staleAfterSec = staleAfterSec; }
}
