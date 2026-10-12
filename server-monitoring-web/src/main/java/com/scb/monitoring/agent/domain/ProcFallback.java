package com.scb.monitoring.agent.domain;

import java.sql.Timestamp;

/** tb_proc_fallback : 데몬 중단 중 하트비트 감시자가 대신 수집한 프로세스 현황 (서버당 1건, 데몬이 다시 뜨면 삭제) */
public class ProcFallback {

    private Long serverId;
    private String daemonState;
    private String reportText;
    private int procCnt;
    private int svcCnt;
    private int recvCnt;
    private Timestamp firstDt;
    private Timestamp collectDt;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getDaemonState() { return daemonState; }
    public void setDaemonState(String daemonState) { this.daemonState = daemonState; }
    public String getReportText() { return reportText; }
    public void setReportText(String reportText) { this.reportText = reportText; }
    public int getProcCnt() { return procCnt; }
    public void setProcCnt(int procCnt) { this.procCnt = procCnt; }
    public int getSvcCnt() { return svcCnt; }
    public void setSvcCnt(int svcCnt) { this.svcCnt = svcCnt; }
    public int getRecvCnt() { return recvCnt; }
    public void setRecvCnt(int recvCnt) { this.recvCnt = recvCnt; }
    public Timestamp getFirstDt() { return firstDt; }
    public void setFirstDt(Timestamp firstDt) { this.firstDt = firstDt; }
    public Timestamp getCollectDt() { return collectDt; }
    public void setCollectDt(Timestamp collectDt) { this.collectDt = collectDt; }
}
