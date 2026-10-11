package com.scb.monitoring.serverlog.domain;

import java.sql.Timestamp;

/** 서버별 로그 요청 주기 (tb_log_policy). 행이 없으면 기본 5분 */
public class LogPolicy {

    public static final int DEFAULT_INTERVAL_MIN = 5;
    /** 화면에서 고를 수 있는 주기 (분, 0 = 사용 안 함) */
    public static final int[] INTERVALS = {0, 1, 5, 10, 30, 60};

    private Long serverId;
    private int intervalMin = DEFAULT_INTERVAL_MIN;
    private String reqNowYn = "N";
    private Timestamp lastReqDt;
    private Timestamp nextReqDt;
    private Timestamp lastRecvDt;
    private String updUser;
    private Timestamp updDt;

    public static LogPolicy defaults(Long serverId) {
        LogPolicy p = new LogPolicy();
        p.serverId = serverId;
        return p;
    }

    public static boolean isAllowedInterval(int min) {
        for (int i : INTERVALS) if (i == min) return true;
        return false;
    }

    public boolean isRequestNow() { return "Y".equals(reqNowYn); }

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public int getIntervalMin() { return intervalMin; }
    public void setIntervalMin(int intervalMin) { this.intervalMin = intervalMin; }
    public String getReqNowYn() { return reqNowYn; }
    public void setReqNowYn(String reqNowYn) { this.reqNowYn = reqNowYn; }
    public Timestamp getLastReqDt() { return lastReqDt; }
    public void setLastReqDt(Timestamp lastReqDt) { this.lastReqDt = lastReqDt; }
    public Timestamp getNextReqDt() { return nextReqDt; }
    public void setNextReqDt(Timestamp nextReqDt) { this.nextReqDt = nextReqDt; }
    public Timestamp getLastRecvDt() { return lastRecvDt; }
    public void setLastRecvDt(Timestamp lastRecvDt) { this.lastRecvDt = lastRecvDt; }
    public String getUpdUser() { return updUser; }
    public void setUpdUser(String updUser) { this.updUser = updUser; }
    public Timestamp getUpdDt() { return updDt; }
    public void setUpdDt(Timestamp updDt) { this.updDt = updDt; }
}
