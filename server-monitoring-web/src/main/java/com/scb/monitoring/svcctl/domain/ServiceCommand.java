package com.scb.monitoring.svcctl.domain;

import java.sql.Timestamp;

/**
 * tb_service_cmd : 서비스 제어 명령.
 * REQUESTED(화면 요청) → SENT(에이전트가 가져감) → DONE / FAILED(결과 보고), 시간 초과 시 EXPIRED
 */
public class ServiceCommand {

    public static final String REQUESTED = "REQUESTED";
    public static final String SENT = "SENT";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

    private Long cmdId;
    private Long serverId;
    private String target;
    private String cmdCd;
    private String stateCd;
    private String reqUser;
    private Timestamp reqDt;
    private Timestamp sentDt;
    private Timestamp endDt;
    private Integer exitCd;
    private String resultMsg;

    public Long getCmdId() { return cmdId; }
    public void setCmdId(Long cmdId) { this.cmdId = cmdId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getCmdCd() { return cmdCd; }
    public void setCmdCd(String cmdCd) { this.cmdCd = cmdCd; }
    public String getStateCd() { return stateCd; }
    public void setStateCd(String stateCd) { this.stateCd = stateCd; }
    public String getReqUser() { return reqUser; }
    public void setReqUser(String reqUser) { this.reqUser = reqUser; }
    public Timestamp getReqDt() { return reqDt; }
    public void setReqDt(Timestamp reqDt) { this.reqDt = reqDt; }
    public Timestamp getSentDt() { return sentDt; }
    public void setSentDt(Timestamp sentDt) { this.sentDt = sentDt; }
    public Timestamp getEndDt() { return endDt; }
    public void setEndDt(Timestamp endDt) { this.endDt = endDt; }
    public Integer getExitCd() { return exitCd; }
    public void setExitCd(Integer exitCd) { this.exitCd = exitCd; }
    public String getResultMsg() { return resultMsg; }
    public void setResultMsg(String resultMsg) { this.resultMsg = resultMsg; }
}
