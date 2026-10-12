package com.scb.monitoring.agent.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 에이전트 보고 (text/plain, Bourne shell 에서 만들기 쉬운 형식).
 * <pre>
 * [sys]       hostname=..  os=..  agent_ver=..
 * [metric]    cpu=18.4  mem=68.2  disk=52  sessions=1420
 * [proc]      proc_id|Y|pid|cpu|mem          (Y=실행 / N=중지)
 * [service]   서비스명|RUNNING|pid[|cpu|mem]  (scb_main.sh status 결과 + PID 의 ps pcpu/pmem, 클라이언트 1.3~)
 * [net]       peer=10.0.13.218|UP|0.52
 * </pre>
 * 형식이 맞지 않는 줄은 무시합니다. 숫자는 범위를 검사합니다.
 */
public class AgentReport {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,50}$");
    private static final int MAX_LINES = 2000;

    final Map<String, String> sys = new HashMap<>();
    final Map<String, String> metric = new HashMap<>();
    final List<ProcState> procs = new ArrayList<>();
    final List<String[]> services = new ArrayList<>();
    /** [ps] 서버 전체 실행 프로세스 (감시자 대신 수집 1.8~) : pid, ppid, user, cpu, mem, etime, args */
    final List<String[]> ps = new ArrayList<>();
    boolean psSection;
    String peer;

    static class ProcState {
        long procId;
        boolean running;
        Integer pid;
        BigDecimal cpu;
        BigDecimal mem;
    }

    // ---- 데몬 중단 중 프로세스 현황 화면 (FallbackView) 용 ----
    public Map<String, String> metrics() { return metric; }
    public List<String[]> serviceStates() { return services; }
    public int procCount() { return procs.size(); }
    public List<String[]> psRows() { return ps; }
    public boolean hasPs() { return psSection; }
    /** [procId, Y/N, pid, cpu, mem] */
    public List<Object[]> procStates() {
        List<Object[]> out = new ArrayList<>();
        for (ProcState p : procs) out.add(new Object[] {p.procId, p.running, p.pid, p.cpu, p.mem});
        return out;
    }

    public static AgentReport parse(String text) {
        AgentReport r = new AgentReport();
        String section = "";
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length && i < MAX_LINES; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase();
                if ("ps".equals(section)) r.psSection = true;
                continue;
            }
            switch (section) {
                case "sys":     putKv(r.sys, line); break;
                case "metric":  putKv(r.metric, line); break;
                case "proc":    r.addProc(line); break;
                case "service": r.addService(line); break;
                case "ps":      r.addPs(line); break;
                case "net":
                    if (line.startsWith("peer=")) r.peer = clip(line.substring(5), 100);
                    break;
                default: break;
            }
        }
        return r;
    }

    private static void putKv(Map<String, String> m, String line) {
        int eq = line.indexOf('=');
        if (eq > 0) m.put(line.substring(0, eq).trim(), clip(line.substring(eq + 1).trim(), 100));
    }

    private void addProc(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length < 5) return;
        try {
            ProcState p = new ProcState();
            p.procId = Long.parseLong(f[0].trim());
            p.running = "Y".equals(f[1].trim());
            p.pid = p.running ? toPid(f[2]) : null;
            p.cpu = p.running ? pct(f[3]) : BigDecimal.ZERO.setScale(1);
            p.mem = p.running ? pct(f[4]) : BigDecimal.ZERO.setScale(1);
            if (p.running && (p.cpu == null || p.mem == null)) return;
            procs.add(p);
        } catch (NumberFormatException e) {
            // 잘못된 줄 무시
        }
    }

    private void addPs(String line) {
        String[] f = line.split("\\|", 7);
        if (f.length < 7) return;
        if (!f[0].trim().matches("\\d{1,10}") || !f[1].trim().matches("\\d{1,10}")) return;
        ps.add(new String[] {f[0].trim(), f[1].trim(), clip(f[2].trim(), 30), clip(f[3].trim(), 10), clip(f[4].trim(), 10),
                clip(f[5].trim(), 20), clip(f[6].trim(), 300)});
    }

    private void addService(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length < 2) return;
        String name = f[0].trim();
        String state = f[1].trim().toUpperCase();
        if (!NAME.matcher(name).matches()) return;
        if (!"RUNNING".equals(state) && !"STOPPED".equals(state)) return;
        Integer pid = f.length > 2 ? toPid(f[2]) : null;
        BigDecimal cpu = f.length > 3 && "RUNNING".equals(state) ? pct(f[3]) : null;
        BigDecimal mem = f.length > 4 && "RUNNING".equals(state) ? pct(f[4]) : null;
        // [5] = "M" : CPU/MEM 칸이 있는 형식(클라이언트 1.3~) → 이력 저장 대상
        services.add(new String[] {name, state, pid == null ? null : String.valueOf(pid),
                cpu == null ? null : cpu.toPlainString(), mem == null ? null : mem.toPlainString(), f.length > 3 ? "M" : null});
    }

    /** 0.0 ~ 100.0 (범위 밖이면 null) */
    static BigDecimal pct(String v) {
        try {
            BigDecimal d = new BigDecimal(v.trim()).setScale(1, BigDecimal.ROUND_HALF_UP);
            return d.signum() < 0 || d.compareTo(new BigDecimal("100.0")) > 0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer toPid(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer toCount(String v) {
        if (v == null) return null;
        try {
            int n = Integer.parseInt(v.trim());
            return n >= 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String clip(String v, int max) {
        return v.length() > max ? v.substring(0, max) : v;
    }
}
