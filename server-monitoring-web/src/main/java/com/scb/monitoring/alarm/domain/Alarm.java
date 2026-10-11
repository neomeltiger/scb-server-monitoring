package com.scb.monitoring.alarm.domain;

import java.sql.Timestamp;

/** 서버 장애 알람 (tb_alarm + 서버 정보) */
public class Alarm {

    public static final String TYPE_SERVER_DOWN = "SERVER_DOWN";
    public static final String TYPE_AGENT_STALE = "AGENT_STALE";
    public static final String LEVEL_CRITICAL = "CRITICAL";
    public static final String LEVEL_WARNING = "WARNING";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_ACK = "ACK";
    public static final String STATE_CLOSED = "CLOSED";

    private Long alarmId;
    private Long serverId;
    private String alarmType;
    private String alarmLevel;
    private String message;
    private String stateCd;
    private Timestamp occurDt;
    private String ackUser;
    private Timestamp ackDt;
    private String actionMemo;
    private Timestamp closeDt;
    // 서버 정보 (조회 시)
    private String systemNm;
    private String ipAddr;
    private String fqdn;
    private String locationNm;

    public Long getAlarmId() { return alarmId; }
    public void setAlarmId(Long alarmId) { this.alarmId = alarmId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getAlarmType() { return alarmType; }
    public void setAlarmType(String alarmType) { this.alarmType = alarmType; }
    public String getAlarmLevel() { return alarmLevel; }
    public void setAlarmLevel(String alarmLevel) { this.alarmLevel = alarmLevel; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStateCd() { return stateCd; }
    public void setStateCd(String stateCd) { this.stateCd = stateCd; }
    public Timestamp getOccurDt() { return occurDt; }
    public void setOccurDt(Timestamp occurDt) { this.occurDt = occurDt; }
    public String getAckUser() { return ackUser; }
    public void setAckUser(String ackUser) { this.ackUser = ackUser; }
    public Timestamp getAckDt() { return ackDt; }
    public void setAckDt(Timestamp ackDt) { this.ackDt = ackDt; }
    public String getActionMemo() { return actionMemo; }
    public void setActionMemo(String actionMemo) { this.actionMemo = actionMemo; }
    public Timestamp getCloseDt() { return closeDt; }
    public void setCloseDt(Timestamp closeDt) { this.closeDt = closeDt; }
    public String getSystemNm() { return systemNm; }
    public void setSystemNm(String systemNm) { this.systemNm = systemNm; }
    public String getIpAddr() { return ipAddr; }
    public void setIpAddr(String ipAddr) { this.ipAddr = ipAddr; }
    public String getFqdn() { return fqdn; }
    public void setFqdn(String fqdn) { this.fqdn = fqdn; }
    public String getLocationNm() { return locationNm; }
    public void setLocationNm(String locationNm) { this.locationNm = locationNm; }
}
