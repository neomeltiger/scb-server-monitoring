package com.scb.monitoring.svcctl.domain;

import java.sql.Timestamp;

/** tb_service : service.list 한 줄 (GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT|ARG|CMDS) + 수집 상태 */
public class ServiceItem {

    private Long svcId;
    private Long serverId;
    private Integer listNo;
    private Integer groupNo;
    private Integer levelNo;
    private String svcNm;
    private String accountNm;
    private String scriptPath;
    private String scriptArg;
    private String cmds;
    private String stateCd;
    private Integer pid;
    private Timestamp stateDt;

    /** CMDS 에 명령이 있는지 (start|stop|test|kill|clean) */
    public boolean supports(String cmd) {
        return cmds != null && ("|" + cmds + "|").contains("|" + cmd + "|");
    }

    public Long getSvcId() { return svcId; }
    public void setSvcId(Long svcId) { this.svcId = svcId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Integer getListNo() { return listNo; }
    public void setListNo(Integer listNo) { this.listNo = listNo; }
    public Integer getGroupNo() { return groupNo; }
    public void setGroupNo(Integer groupNo) { this.groupNo = groupNo; }
    public Integer getLevelNo() { return levelNo; }
    public void setLevelNo(Integer levelNo) { this.levelNo = levelNo; }
    public String getSvcNm() { return svcNm; }
    public void setSvcNm(String svcNm) { this.svcNm = svcNm; }
    public String getAccountNm() { return accountNm; }
    public void setAccountNm(String accountNm) { this.accountNm = accountNm; }
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }
    public String getScriptArg() { return scriptArg; }
    public void setScriptArg(String scriptArg) { this.scriptArg = scriptArg; }
    public String getCmds() { return cmds; }
    public void setCmds(String cmds) { this.cmds = cmds; }
    public String getStateCd() { return stateCd; }
    public void setStateCd(String stateCd) { this.stateCd = stateCd; }
    public Integer getPid() { return pid; }
    public void setPid(Integer pid) { this.pid = pid; }
    public Timestamp getStateDt() { return stateDt; }
    public void setStateDt(Timestamp stateDt) { this.stateDt = stateDt; }
}
