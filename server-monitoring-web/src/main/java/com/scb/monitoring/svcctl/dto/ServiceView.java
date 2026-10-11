package com.scb.monitoring.svcctl.dto;

import java.text.SimpleDateFormat;

import com.scb.monitoring.svcctl.domain.ServiceItem;

/** 서비스 1건 응답 (service.list 한 줄 + 상태) */
public class ServiceView {

    private Long id;
    private Integer no;
    private Integer group;
    private Integer level;
    private String name;
    private String account;
    private String script;
    private String arg;
    private String cmds;
    private String state;
    private String pid;
    private String stateDt;

    public static ServiceView of(ServiceItem s) {
        ServiceView v = new ServiceView();
        v.id = s.getSvcId();
        v.no = s.getListNo();
        v.group = s.getGroupNo();
        v.level = s.getLevelNo();
        v.name = s.getSvcNm();
        v.account = s.getAccountNm();
        v.script = s.getScriptPath();
        v.arg = s.getScriptArg() == null ? "" : s.getScriptArg();
        v.cmds = s.getCmds();
        v.state = s.getStateCd();
        v.pid = s.getPid() == null ? "-" : String.valueOf(s.getPid());
        v.stateDt = s.getStateDt() == null ? null : new SimpleDateFormat("MM-dd HH:mm:ss").format(s.getStateDt());
        return v;
    }

    public Long getId() { return id; }
    public Integer getNo() { return no; }
    public Integer getGroup() { return group; }
    public Integer getLevel() { return level; }
    public String getName() { return name; }
    public String getAccount() { return account; }
    public String getScript() { return script; }
    public String getArg() { return arg; }
    public String getCmds() { return cmds; }
    public String getState() { return state; }
    public String getPid() { return pid; }
    public String getStateDt() { return stateDt; }
}
