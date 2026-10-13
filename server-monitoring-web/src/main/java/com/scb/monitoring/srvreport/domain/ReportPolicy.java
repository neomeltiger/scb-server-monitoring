package com.scb.monitoring.srvreport.domain;

import java.sql.Timestamp;

/**
 * 서버 점검 리포트 자동 요청 주기 (tb_report_policy) - 관리자가 서버 점검 리포트 화면에서 정함.
 * interval_hr = 0 이면 사용 안 함. 때가 되면 웹이 관리자 대신 [리포트 요청] 을 만듦 (ServerReportService.runSchedules).
 */
public class ReportPolicy {

    private Long serverId;
    private int intervalHr;
    private String sections;
    private Timestamp nextDt;
    private Timestamp lastReqDt;
    private String updUser;
    private Timestamp updDt;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public int getIntervalHr() { return intervalHr; }
    public void setIntervalHr(int intervalHr) { this.intervalHr = intervalHr; }
    public String getSections() { return sections; }
    public void setSections(String sections) { this.sections = sections; }
    public Timestamp getNextDt() { return nextDt; }
    public void setNextDt(Timestamp nextDt) { this.nextDt = nextDt; }
    public Timestamp getLastReqDt() { return lastReqDt; }
    public void setLastReqDt(Timestamp lastReqDt) { this.lastReqDt = lastReqDt; }
    public String getUpdUser() { return updUser; }
    public void setUpdUser(String updUser) { this.updUser = updUser; }
    public Timestamp getUpdDt() { return updDt; }
    public void setUpdDt(Timestamp updDt) { this.updDt = updDt; }
}
