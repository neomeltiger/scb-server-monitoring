package com.scb.monitoring.alarm.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import com.scb.monitoring.alarm.domain.Alarm;

/** 알람 응답 (메인 홈 경고 배너 / 알람 이력) */
public class AlarmView {

    private Long alarmId;
    private Long serverId;
    private String alarmType;
    private String typeLabel;
    private String alarmLevel;
    private String message;
    private String stateCd;
    private String stateLabel;
    private String occurDt;
    private Long elapsedSec;
    private String ackUser;
    private String ackDt;
    private String actionMemo;
    private String closeDt;
    private String systemNm;
    private String ipAddr;
    private String fqdn;
    private String location;

    public static AlarmView of(Alarm a) {
        AlarmView v = new AlarmView();
        v.alarmId = a.getAlarmId();
        v.serverId = a.getServerId();
        v.alarmType = a.getAlarmType();
        v.typeLabel = Alarm.TYPE_SERVER_DOWN.equals(a.getAlarmType()) ? "서버 다운" : "에이전트 보고 끊김";
        v.alarmLevel = a.getAlarmLevel();
        v.message = a.getMessage();
        v.stateCd = a.getStateCd();
        v.stateLabel = Alarm.STATE_OPEN.equals(a.getStateCd()) ? "미확인"
                : Alarm.STATE_ACK.equals(a.getStateCd()) ? "조치 중" : "해소";
        v.occurDt = fmt(a.getOccurDt());
        if (a.getOccurDt() != null) {
            long end = a.getCloseDt() != null ? a.getCloseDt().getTime() : System.currentTimeMillis();
            v.elapsedSec = Math.max(0, (end - a.getOccurDt().getTime()) / 1000);
        }
        v.ackUser = a.getAckUser();
        v.ackDt = fmt(a.getAckDt());
        v.actionMemo = a.getActionMemo();
        v.closeDt = fmt(a.getCloseDt());
        v.systemNm = a.getSystemNm();
        v.ipAddr = a.getIpAddr();
        v.fqdn = a.getFqdn();
        v.location = a.getLocationNm();
        return v;
    }

    private static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    public Long getAlarmId() { return alarmId; }
    public Long getServerId() { return serverId; }
    public String getAlarmType() { return alarmType; }
    public String getTypeLabel() { return typeLabel; }
    public String getAlarmLevel() { return alarmLevel; }
    public String getMessage() { return message; }
    public String getStateCd() { return stateCd; }
    public String getStateLabel() { return stateLabel; }
    public String getOccurDt() { return occurDt; }
    public Long getElapsedSec() { return elapsedSec; }
    public String getAckUser() { return ackUser; }
    public String getAckDt() { return ackDt; }
    public String getActionMemo() { return actionMemo; }
    public String getCloseDt() { return closeDt; }
    public String getSystemNm() { return systemNm; }
    public String getIpAddr() { return ipAddr; }
    public String getFqdn() { return fqdn; }
    public String getLocation() { return location; }
}
