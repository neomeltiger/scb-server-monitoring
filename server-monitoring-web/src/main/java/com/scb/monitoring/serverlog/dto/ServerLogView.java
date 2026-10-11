package com.scb.monitoring.serverlog.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.serverlog.domain.LogPolicy;
import com.scb.monitoring.serverlog.domain.LogSnapshot;
import com.scb.monitoring.serverlog.domain.LogSource;

/** 서버 로그 화면 응답 : 요청 주기 + 수집 대상 + 에이전트 연결 상태 */
public class ServerLogView {

    private Long serverId;
    private String systemName;
    private Policy policy;
    private List<Source> sources = new ArrayList<>();
    private AgentView agent;
    private int[] intervals = LogPolicy.INTERVALS;

    public static ServerLogView of(Long serverId, String systemName, LogPolicy p, List<LogSource> sources, AgentView agent) {
        ServerLogView v = new ServerLogView();
        v.serverId = serverId;
        v.systemName = systemName;
        v.policy = Policy.of(p);
        for (LogSource s : sources) v.sources.add(Source.of(s));
        v.agent = agent;
        return v;
    }

    static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    public Long getServerId() { return serverId; }
    public String getSystemName() { return systemName; }
    public Policy getPolicy() { return policy; }
    public List<Source> getSources() { return sources; }
    public AgentView getAgent() { return agent; }
    public int[] getIntervals() { return intervals; }

    public static class Policy {
        private int intervalMin;
        private boolean requestPending;
        private String lastReqDt;
        private String nextReqDt;
        private String lastRecvDt;
        private String updUser;

        static Policy of(LogPolicy p) {
            Policy v = new Policy();
            v.intervalMin = p.getIntervalMin();
            v.requestPending = p.isRequestNow();
            v.lastReqDt = fmt(p.getLastReqDt());
            v.nextReqDt = p.getIntervalMin() > 0 ? fmt(p.getNextReqDt()) : null;
            v.lastRecvDt = fmt(p.getLastRecvDt());
            v.updUser = p.getUpdUser();
            return v;
        }

        public int getIntervalMin() { return intervalMin; }
        public boolean isRequestPending() { return requestPending; }
        public String getLastReqDt() { return lastReqDt; }
        public String getNextReqDt() { return nextReqDt; }
        public String getLastRecvDt() { return lastRecvDt; }
        public String getUpdUser() { return updUser; }
    }

    public static class Source {
        private Long id;
        private String name;
        private String path;
        private int tailLines;
        private boolean use;
        private String lastStatus;
        private String lastDt;

        public static Source of(LogSource s) {
            Source v = new Source();
            v.id = s.getLogId();
            v.name = s.getLogNm();
            v.path = s.getLogPath();
            v.tailLines = s.getTailLines();
            v.use = s.isUse();
            v.lastStatus = s.getLastStatus();
            v.lastDt = fmt(s.getLastDt());
            return v;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getPath() { return path; }
        public int getTailLines() { return tailLines; }
        public boolean isUse() { return use; }
        public String getLastStatus() { return lastStatus; }
        public String getLastDt() { return lastDt; }
    }

    /** 수집 결과 (목록은 content 없음) */
    public static class Snapshot {
        private Long id;
        private Long logId;
        private String collectDt;
        private String status;
        private int lineCnt;
        private int byteCnt;
        private String content;

        public static Snapshot of(LogSnapshot s) {
            Snapshot v = new Snapshot();
            v.id = s.getSnapId();
            v.logId = s.getLogId();
            v.collectDt = fmt(s.getCollectDt());
            v.status = s.getStatusCd();
            v.lineCnt = s.getLineCnt();
            v.byteCnt = s.getByteCnt();
            v.content = s.getContent();
            return v;
        }

        public Long getId() { return id; }
        public Long getLogId() { return logId; }
        public String getCollectDt() { return collectDt; }
        public String getStatus() { return status; }
        public int getLineCnt() { return lineCnt; }
        public int getByteCnt() { return byteCnt; }
        public String getContent() { return content; }
    }
}
