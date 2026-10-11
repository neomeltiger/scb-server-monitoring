package com.scb.monitoring.serverlog.domain;

import java.sql.Timestamp;

/** 서버별 수집 대상 로그 파일 (tb_log_source) */
public class LogSource {

    private Long logId;
    private Long serverId;
    private String logNm;
    private String logPath;
    private int tailLines = 200;
    private String useYn = "Y";
    private int sortOrd;
    private String lastStatus;
    private Timestamp lastDt;

    public boolean isUse() { return "Y".equals(useYn); }

    public Long getLogId() { return logId; }
    public void setLogId(Long logId) { this.logId = logId; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getLogNm() { return logNm; }
    public void setLogNm(String logNm) { this.logNm = logNm; }
    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }
    public int getTailLines() { return tailLines; }
    public void setTailLines(int tailLines) { this.tailLines = tailLines; }
    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }
    public int getSortOrd() { return sortOrd; }
    public void setSortOrd(int sortOrd) { this.sortOrd = sortOrd; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public Timestamp getLastDt() { return lastDt; }
    public void setLastDt(Timestamp lastDt) { this.lastDt = lastDt; }
}
