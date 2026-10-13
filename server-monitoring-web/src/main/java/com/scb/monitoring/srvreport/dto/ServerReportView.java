package com.scb.monitoring.srvreport.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;

import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.srvreport.domain.ServerReport;

/** 서버 점검 리포트 화면 - 서버 / 에이전트 상태 / 리포트 목록 */
public class ServerReportView {

    /** 요청 후 이 시간(초)이 지나도 결과가 없으면 "응답 없음" (다시 요청 가능) */
    public static final int OVERDUE_SEC = 600;

    private Long serverId;
    private String serverName;
    private String ip;
    /** 에이전트 상태 (NONE / WAITING / OK / STALE / LOST / PAUSED / STOPPED) */
    private String agentState;
    private String agentVer;
    private String lastPollDt;
    /** 클라이언트가 리포트를 지원 (1.12 이상, 버전을 모르면 true) */
    private boolean clientSupported;
    private List<Item> reports;

    public static ServerReportView of(Long serverId, String name, String ip, AgentView av, boolean supported, List<Item> reports) {
        ServerReportView v = new ServerReportView();
        v.serverId = serverId;
        v.serverName = name;
        v.ip = ip;
        v.agentState = av.getState();
        v.agentVer = av.getAgentVer();
        v.lastPollDt = av.getLastPollDt();
        v.clientSupported = supported;
        v.reports = reports;
        return v;
    }

    static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    /** 목록 한 줄 (내용 없음) */
    public static class Item {
        private Long id;
        private String sections;
        private String status;
        private String reqUser;
        private String reqDt;
        private String sentDt;
        private String doneDt;
        private int warnCnt;
        private int byteCnt;
        /** 진행 중(REQUESTED / SENT)인데 OVERDUE_SEC 이 지남 */
        private boolean overdue;

        public static Item of(ServerReport r) {
            Item i = new Item();
            i.id = r.getReportId();
            i.sections = r.getSections();
            i.status = r.getStatusCd();
            i.reqUser = r.getReqUser();
            i.reqDt = fmt(r.getReqDt());
            i.sentDt = fmt(r.getSentDt());
            i.doneDt = fmt(r.getDoneDt());
            i.warnCnt = r.getWarnCnt();
            i.byteCnt = r.getByteCnt();
            Timestamp since = r.getSentDt() != null ? r.getSentDt() : r.getReqDt();
            i.overdue = r.isActive() && since != null && System.currentTimeMillis() - since.getTime() > OVERDUE_SEC * 1000L;
            return i;
        }

        public Long getId() { return id; }
        public String getSections() { return sections; }
        public String getStatus() { return status; }
        public String getReqUser() { return reqUser; }
        public String getReqDt() { return reqDt; }
        public String getSentDt() { return sentDt; }
        public String getDoneDt() { return doneDt; }
        public int getWarnCnt() { return warnCnt; }
        public int getByteCnt() { return byteCnt; }
        public boolean isOverdue() { return overdue; }
    }

    /** 리포트 내용 */
    public static class Detail extends Item {
        private String content;

        public static Detail of(ServerReport r) {
            Detail d = new Detail();
            Item i = Item.of(r);
            d.copy(i);
            d.content = r.getContent();
            return d;
        }

        private void copy(Item i) {
            super.id = i.id;
            super.sections = i.sections;
            super.status = i.status;
            super.reqUser = i.reqUser;
            super.reqDt = i.reqDt;
            super.sentDt = i.sentDt;
            super.doneDt = i.doneDt;
            super.warnCnt = i.warnCnt;
            super.byteCnt = i.byteCnt;
            super.overdue = i.overdue;
        }

        public String getContent() { return content; }
    }

    public Long getServerId() { return serverId; }
    public String getServerName() { return serverName; }
    public String getIp() { return ip; }
    public String getAgentState() { return agentState; }
    public String getAgentVer() { return agentVer; }
    public String getLastPollDt() { return lastPollDt; }
    public boolean isClientSupported() { return clientSupported; }
    public List<Item> getReports() { return reports; }
}
