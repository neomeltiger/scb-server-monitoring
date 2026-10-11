package com.scb.monitoring.serverlog.domain;

import java.sql.Timestamp;

/** 수집된 로그 (tb_log_snapshot) - 로그별 최근 N회 보관 */
public class LogSnapshot {

    public static final String OK = "OK";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String DENIED = "DENIED";
    public static final String ERROR = "ERROR";

    private Long snapId;
    private Long logId;
    private Long serverId;
    private Timestamp collectDt;
    private String statusCd;
    private int lineCnt;
    private int byteCnt;
    private String content;

    public static String normalizeStatus(String s) {
        if (OK.equals(s) || NOT_FOUND.equals(s) || DENIED.equals(s)) return s;
        return ERROR;
    }

    public Long getSnapId() { return snapId; }
    public void setSnapId(Long snapId) { this.snapId = snapId; }
    public Long getLogId() { return logId; }
    public void setLogId(Long logId) { this.logId = logId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Timestamp getCollectDt() { return collectDt; }
    public void setCollectDt(Timestamp collectDt) { this.collectDt = collectDt; }
    public String getStatusCd() { return statusCd; }
    public void setStatusCd(String statusCd) { this.statusCd = statusCd; }
    public int getLineCnt() { return lineCnt; }
    public void setLineCnt(int lineCnt) { this.lineCnt = lineCnt; }
    public int getByteCnt() { return byteCnt; }
    public void setByteCnt(int byteCnt) { this.byteCnt = byteCnt; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
