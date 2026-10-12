package com.scb.monitoring.agent.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.scb.monitoring.agent.domain.ProcFallback;
import com.scb.monitoring.agent.service.AgentReport;
import com.scb.monitoring.svcctl.domain.ServiceItem;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerProcess;

/** 데몬 중단 중 프로세스 현황 화면 (/fallback) - 서버 1대 */
public class FallbackView {

    private Long serverId;
    private String systemNm;
    private String ipAddr;
    private String location;
    /** 수집 당시 데몬 상태 : DEAD(비정상 종료) / STOPPED(정지) */
    private String daemonState;
    private String daemonIssue;
    private String hbState;
    private boolean serverDown;
    private boolean canRestart;
    private boolean restartPending;
    private String firstDt;
    private String collectDt;
    private long collectAgoSec;
    /** 새 정보가 STALE_SEC 넘게 없음 (감시자 수집 지연 또는 서버 다운) */
    private boolean stale;
    private int recvCnt;
    private String cpu;
    private String mem;
    private String disk;
    private int procRunning;
    private int svcRunning;
    private List<Map<String, Object>> processes = new ArrayList<>();
    private List<Map<String, Object>> services = new ArrayList<>();

    public static FallbackView of(Server s, ProcFallback f, AgentReport r, AgentView av, Map<Long, ServerProcess> procMeta,
                                  Map<String, ServiceItem> svcMeta, List<String> svcOrder, long now, int staleSec) {
        FallbackView v = new FallbackView();
        v.serverId = s.getServerId();
        v.systemNm = s.getSystemNm();
        v.ipAddr = s.getIpAddr();
        v.location = s.getLocationNm();
        v.daemonState = f.getDaemonState();
        v.daemonIssue = av.getDaemonIssue();
        v.hbState = av.getHbState();
        v.serverDown = av.isServerDown();
        v.canRestart = av.isCanRestart();
        v.restartPending = av.isRestartPending();
        v.firstDt = fmt(f.getFirstDt());
        v.collectDt = fmt(f.getCollectDt());
        v.collectAgoSec = f.getCollectDt() == null ? 0 : Math.max(0, (now - f.getCollectDt().getTime()) / 1000);
        v.stale = v.collectAgoSec > staleSec || av.isServerDown();
        v.recvCnt = f.getRecvCnt();
        v.cpu = r.metrics().get("cpu");
        v.mem = r.metrics().get("mem");
        v.disk = r.metrics().get("disk");

        for (Object[] p : r.procStates()) {
            ServerProcess meta = procMeta.get((Long) p[0]);
            if (meta == null) continue;                     // 다른 서버 / 지워진 프로세스
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p[0]);
            m.put("name", meta.getProcNm());
            m.put("path", meta.getExecPath());
            m.put("running", p[1]);
            m.put("pid", p[2]);
            m.put("cpu", str((BigDecimal) p[3]));
            m.put("mem", str((BigDecimal) p[4]));
            if (Boolean.TRUE.equals(p[1])) v.procRunning++;
            v.processes.add(m);
        }
        // 서비스는 service.list 순서대로 (목록에 없는 이름은 뒤에)
        Map<String, String[]> byName = new HashMap<>();
        for (String[] st : r.serviceStates()) byName.put(st[0], st);
        List<String> names = new ArrayList<>();
        for (String n : svcOrder) if (byName.containsKey(n)) names.add(n);
        for (String n : byName.keySet()) if (!names.contains(n)) names.add(n);
        for (String n : names) {
            String[] st = byName.get(n);
            ServiceItem meta = svcMeta.get(n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n);
            m.put("state", st[1]);
            m.put("pid", st[2]);
            m.put("cpu", st[3]);
            m.put("mem", st[4]);
            m.put("group", meta == null ? null : meta.getGroupNo());
            m.put("level", meta == null ? null : meta.getLevelNo());
            if ("RUNNING".equals(st[1])) v.svcRunning++;
            v.services.add(m);
        }
        return v;
    }

    private static String str(BigDecimal d) { return d == null ? null : d.toPlainString(); }

    private static String fmt(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    public Long getServerId() { return serverId; }
    public String getSystemNm() { return systemNm; }
    public String getIpAddr() { return ipAddr; }
    public String getLocation() { return location; }
    public String getDaemonState() { return daemonState; }
    public String getDaemonIssue() { return daemonIssue; }
    public String getHbState() { return hbState; }
    public boolean isServerDown() { return serverDown; }
    public boolean isCanRestart() { return canRestart; }
    public boolean isRestartPending() { return restartPending; }
    public String getFirstDt() { return firstDt; }
    public String getCollectDt() { return collectDt; }
    public long getCollectAgoSec() { return collectAgoSec; }
    public boolean isStale() { return stale; }
    public int getRecvCnt() { return recvCnt; }
    public String getCpu() { return cpu; }
    public String getMem() { return mem; }
    public String getDisk() { return disk; }
    public int getProcRunning() { return procRunning; }
    public int getSvcRunning() { return svcRunning; }
    public List<Map<String, Object>> getProcesses() { return processes; }
    public List<Map<String, Object>> getServices() { return services; }
}
