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
    /** 서버 전체 실행 프로세스 (하트비트 감시자 제외) - 클라이언트 1.8 미만이면 psCollected=false */
    private boolean psCollected;
    private List<Map<String, Object>> psList = new ArrayList<>();
    /** 감시자 버전 / 대신 수집 결과 (하트비트가 알림, 1.9~) */
    private String watchdogVer;
    private String fbStat;
    /** 데몬이 멈췄는데 아직 대신 수집한 정보가 없음 → pendingReason / pendingAction 으로 원인·조치 */
    private boolean pending;
    private String pendingReason;
    private String pendingAction;

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
        // 서버 전체 실행 프로세스 : 주요 프로세스(실행 경로)·서비스(스크립트 경로) 와 맞으면 표시
        v.psCollected = r.hasPs();
        for (String[] p : r.psRows()) {
            String args = p[6];
            String tag = null;
            for (ServerProcess sp : procMeta.values()) {
                if (sp.getExecPath() != null && !sp.getExecPath().isEmpty() && args.contains(sp.getExecPath())) { tag = "주요: " + sp.getProcNm(); break; }
            }
            if (tag == null) {
                for (ServiceItem it : svcMeta.values()) {
                    if (it.getScriptPath() != null && !it.getScriptPath().isEmpty() && args.contains(it.getScriptPath())) { tag = "서비스: " + it.getSvcNm(); break; }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pid", p[0]);
            m.put("ppid", p[1]);
            m.put("user", p[2]);
            m.put("cpu", p[3]);
            m.put("mem", p[4]);
            m.put("etime", p[5]);
            m.put("args", args);
            m.put("tag", tag);
            v.psList.add(m);
        }
        return v;
    }

    /** 데몬이 멈췄는데 정보가 없는 서버 : 원인과 조치 */
    /** @param known 웹이 기동한 뒤 이 서버의 하트비트로 감시자 정보를 받았는지 (아직이면 버전을 판단하지 않음) */
    public static FallbackView pending(Server s, AgentView av, boolean known, String ver, String fb, boolean watchdogClient) {
        FallbackView v = new FallbackView();
        v.serverId = s.getServerId();
        v.systemNm = s.getSystemNm();
        v.ipAddr = s.getIpAddr();
        v.location = s.getLocationNm();
        v.daemonIssue = av.getDaemonIssue();
        v.daemonState = "데몬 비정상 종료".equals(av.getDaemonIssue()) ? "DEAD" : "STOPPED";
        v.hbState = av.getHbState();
        v.canRestart = av.isCanRestart();
        v.restartPending = av.isRestartPending();
        v.pending = true;
        v.setWatchdog(ver, fb);
        String stop = "서버에서 root 로 bin/scb_client.sh stopall → bin/scb_client.sh start (감시자를 새 버전으로 다시 실행)";
        if (!watchdogClient) {
            v.pendingReason = "하트비트 감시자가 없는 이전 클라이언트(1.4 이하)입니다. 데몬이 멈추면 프로세스 정보를 대신 수집할 수 없습니다.";
            v.pendingAction = "클라이언트 1.9 로 업그레이드 후 " + stop;
        } else if (!known) {
            v.pendingReason = "데몬이 멈췄습니다. 하트비트 감시자 정보를 확인하는 중입니다 (다음 하트비트).";
            v.pendingAction = null;
        } else if (ver == null || olderThan(ver, 1, 7)) {
            v.pendingReason = "서버에서 돌고 있는 하트비트 감시자가 이전 버전(" + (ver == null ? "1.8 이하" : ver)
                    + ")이라 프로세스 정보를 대신 수집하지 않습니다. (tar 를 새로 풀고 stop / start 만 하면 이전 감시자가 계속 돕니다)";
            v.pendingAction = stop;
        } else if (fb != null && fb.startsWith("FAIL")) {
            String code = fb.substring(fb.indexOf('_') + 1);
            v.pendingReason = "감시자가 대신 수집한 정보를 보내지 못했습니다 (" + fb + ")."
                    + ("404".equals(code) ? " 웹 서버가 이전 버전입니다 - 웹을 최신으로 다시 배포하세요."
                    : "409".equals(code) ? " 웹이 데몬을 실행 중으로 보고 있습니다 - 잠시 후 다시 확인하세요."
                    : "000".equals(code) ? " 웹 서버에 연결하지 못했습니다 - 네트워크 / 방화벽을 확인하세요."
                    : "TIMEOUT".equals(code) ? " 수집이 2분 넘게 끝나지 않았습니다 - 서버에서 scb_main.sh status 응답을 확인하세요."
                    : "");
            v.pendingAction = "서버의 log/scb_client.log 에서 HEARTBEAT / 대신 수집 기록을 확인하세요.";
        } else {
            v.pendingReason = "데몬이 멈췄습니다. 하트비트 감시자가 프로세스 정보를 수집하는 중입니다 (최대 30초).";
            v.pendingAction = null;
        }
        return v;
    }

    static boolean olderThan(String ver, int major, int minor) {
        try {
            String[] p = ver.trim().split("\\.");
            int a = Integer.parseInt(p[0]);
            int b = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9].*$", "")) : 0;
            return a < major || (a == major && b < minor);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    public void setWatchdog(String ver, String fb) {
        this.watchdogVer = ver;
        this.fbStat = fb;
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
    public boolean isPsCollected() { return psCollected; }
    public List<Map<String, Object>> getPsList() { return psList; }
    public String getWatchdogVer() { return watchdogVer; }
    public String getFbStat() { return fbStat; }
    public boolean isPending() { return pending; }
    public String getPendingReason() { return pendingReason; }
    public String getPendingAction() { return pendingAction; }
}
