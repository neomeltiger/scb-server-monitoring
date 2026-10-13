package com.scb.monitoring.srvreport.domain;

import java.sql.Timestamp;

/**
 * 서버 점검 리포트 (tb_server_report) - 웹 관리자 화면에서 요청할 때만 svr_collect_client 가 만들어 보냄.
 * <pre>
 *  REQUESTED : 요청됨 (에이전트 데몬이 웹을 확인할 때 rpt|ID|분야 로 전달)
 *  SENT      : 에이전트에 전달됨 (서버에서 collect_report.sh 실행 중)
 *  DONE      : 받음 / ERROR : 서버에서 실행 오류
 * </pre>
 */
public class ServerReport {

    public static final String REQUESTED = "REQUESTED";
    public static final String SENT = "SENT";
    public static final String DONE = "DONE";
    public static final String ERROR = "ERROR";

    private Long reportId;
    private Long serverId;
    private String sections;
    private String statusCd;
    private String reqUser;
    private Timestamp reqDt;
    private Timestamp sentDt;
    private Timestamp doneDt;
    private int warnCnt;
    private int byteCnt;
    private String content;

    public boolean isActive() {
        return REQUESTED.equals(statusCd) || SENT.equals(statusCd);
    }

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getSections() { return sections; }
    public void setSections(String sections) { this.sections = sections; }
    public String getStatusCd() { return statusCd; }
    public void setStatusCd(String statusCd) { this.statusCd = statusCd; }
    public String getReqUser() { return reqUser; }
    public void setReqUser(String reqUser) { this.reqUser = reqUser; }
    public Timestamp getReqDt() { return reqDt; }
    public void setReqDt(Timestamp reqDt) { this.reqDt = reqDt; }
    public Timestamp getSentDt() { return sentDt; }
    public void setSentDt(Timestamp sentDt) { this.sentDt = sentDt; }
    public Timestamp getDoneDt() { return doneDt; }
    public void setDoneDt(Timestamp doneDt) { this.doneDt = doneDt; }
    public int getWarnCnt() { return warnCnt; }
    public void setWarnCnt(int warnCnt) { this.warnCnt = warnCnt; }
    public int getByteCnt() { return byteCnt; }
    public void setByteCnt(int byteCnt) { this.byteCnt = byteCnt; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
