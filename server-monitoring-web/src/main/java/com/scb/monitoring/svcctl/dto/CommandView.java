package com.scb.monitoring.svcctl.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import com.scb.monitoring.svcctl.domain.ServiceCommand;

/** 제어 명령 1건 응답 */
public class CommandView {

    private Long id;
    private String target;
    private String cmd;
    private String state;
    private String reqUser;
    private String reqDt;
    private String sentDt;
    private String endDt;
    private Integer exitCode;
    private String result;

    public static CommandView of(ServiceCommand c) {
        CommandView v = new CommandView();
        v.id = c.getCmdId();
        v.target = c.getTarget();
        v.cmd = c.getCmdCd();
        v.state = c.getStateCd();
        v.reqUser = c.getReqUser();
        v.reqDt = fmt(c.getReqDt());
        v.sentDt = fmt(c.getSentDt());
        v.endDt = fmt(c.getEndDt());
        v.exitCode = c.getExitCd();
        v.result = c.getResultMsg();
        return v;
    }

    private static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("MM-dd HH:mm:ss").format(t);
    }

    public Long getId() { return id; }
    public String getTarget() { return target; }
    public String getCmd() { return cmd; }
    public String getState() { return state; }
    public String getReqUser() { return reqUser; }
    public String getReqDt() { return reqDt; }
    public String getSentDt() { return sentDt; }
    public String getEndDt() { return endDt; }
    public Integer getExitCode() { return exitCode; }
    public String getResult() { return result; }
}
