package com.scb.monitoring.agent.domain;

import java.sql.Timestamp;

/** tb_heartbeat_hist : 하트비트 이벤트 (START 수신 시작 / LOST 끊김 / RECOVER 복구 / STOP 데몬 종료 / INTERVAL 주기 변경) */
public class HeartbeatHist {

    public static final String E_START = "START";
    public static final String E_LOST = "LOST";
    public static final String E_RECOVER = "RECOVER";
    public static final String E_STOP = "STOP";
    public static final String E_INTERVAL = "INTERVAL";
    /** 데몬 중단 (서버 정상) / 데몬 다시 실행 / 웹 재시작 요청 */
    public static final String E_D_DOWN = "D_DOWN";
    public static final String E_D_UP = "D_UP";
    public static final String E_RESTART = "RESTART";

    private Long histId;
    private Long serverId;
    private String systemNm;
    private String eventCd;
    private Timestamp eventDt;
    private Integer daemonPid;
    private Integer gapSec;
    private String detail;

    public Long getHistId() { return histId; }
    public void setHistId(Long histId) { this.histId = histId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getSystemNm() { return systemNm; }
    public void setSystemNm(String systemNm) { this.systemNm = systemNm; }
    public String getEventCd() { return eventCd; }
    public void setEventCd(String eventCd) { this.eventCd = eventCd; }
    public Timestamp getEventDt() { return eventDt; }
    public void setEventDt(Timestamp eventDt) { this.eventDt = eventDt; }
    public Integer getDaemonPid() { return daemonPid; }
    public void setDaemonPid(Integer daemonPid) { this.daemonPid = daemonPid; }
    public Integer getGapSec() { return gapSec; }
    public void setGapSec(Integer gapSec) { this.gapSec = gapSec; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
