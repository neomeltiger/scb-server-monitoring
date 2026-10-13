package com.scb.monitoring.srvreport.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * 웹 기본 리포트 (클라이언트 1.11 이하) 를 만들 때 읽는 웹 DB 자료 한 줄.
 * 자원 · 서비스 · 등록 프로세스 · 서버 로그 수집본 · 알람 · 제어 명령을 같은 모양으로 받음 (ServerReportMapper.selectBasic*)
 */
public class BasicRow {

    private String itemNm;       // 서비스·프로세스·로그 이름, 알람 종류, 제어 대상
    private String itemPath;     // 실행 경로 / 로그 경로
    private String stateCd;      // RUNNING·STOPPED / Y·N / OK·DENIED / OPEN·CLOSED / DONE·FAILED
    private String levelCd;      // 알람 등급
    private String cmdCd;        // 제어 명령
    private String userNm;       // 서비스 계정 / 요청자
    private String msg;          // 알람 메시지 / 제어 결과
    private Integer pid;
    private Integer exitCd;
    private Integer cnt;
    private BigDecimal cpuPct;
    private BigDecimal memPct;
    private BigDecimal diskPct;
    private Integer sessionCnt;
    private Timestamp dt;
    private Timestamp endDt;
    private String content;

    public String getItemNm() { return itemNm; }
    public void setItemNm(String itemNm) { this.itemNm = itemNm; }
    public String getItemPath() { return itemPath; }
    public void setItemPath(String itemPath) { this.itemPath = itemPath; }
    public String getStateCd() { return stateCd; }
    public void setStateCd(String stateCd) { this.stateCd = stateCd; }
    public String getLevelCd() { return levelCd; }
    public void setLevelCd(String levelCd) { this.levelCd = levelCd; }
    public String getCmdCd() { return cmdCd; }
    public void setCmdCd(String cmdCd) { this.cmdCd = cmdCd; }
    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public Integer getPid() { return pid; }
    public void setPid(Integer pid) { this.pid = pid; }
    public Integer getExitCd() { return exitCd; }
    public void setExitCd(Integer exitCd) { this.exitCd = exitCd; }
    public Integer getCnt() { return cnt; }
    public void setCnt(Integer cnt) { this.cnt = cnt; }
    public BigDecimal getCpuPct() { return cpuPct; }
    public void setCpuPct(BigDecimal cpuPct) { this.cpuPct = cpuPct; }
    public BigDecimal getMemPct() { return memPct; }
    public void setMemPct(BigDecimal memPct) { this.memPct = memPct; }
    public BigDecimal getDiskPct() { return diskPct; }
    public void setDiskPct(BigDecimal diskPct) { this.diskPct = diskPct; }
    public Integer getSessionCnt() { return sessionCnt; }
    public void setSessionCnt(Integer sessionCnt) { this.sessionCnt = sessionCnt; }
    public Timestamp getDt() { return dt; }
    public void setDt(Timestamp dt) { this.dt = dt; }
    public Timestamp getEndDt() { return endDt; }
    public void setEndDt(Timestamp endDt) { this.endDt = endDt; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
