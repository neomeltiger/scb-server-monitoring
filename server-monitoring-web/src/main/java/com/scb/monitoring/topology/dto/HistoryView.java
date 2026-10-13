package com.scb.monitoring.topology.dto;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.scb.monitoring.topology.domain.MetricHist;

/**
 * 서버 1대의 수집 이력 (메인 홈 서버별 그래프).
 * source : 가장 최근 값의 출처 (AGENT = 에이전트 수집, SAMPLE = 샘플 데이터)
 */
public class HistoryView {

    private String source;
    private String lastTime;
    private final List<String> times = new ArrayList<>();
    /** 수집 시각 (epoch ms) - 대시보드 시간축 (서버마다 수집 주기가 달라도 시각으로 맞춤) */
    private final List<Long> ts = new ArrayList<>();
    private final List<Double> cpu = new ArrayList<>();
    private final List<Double> mem = new ArrayList<>();
    private final List<Double> disk = new ArrayList<>();
    private final List<Integer> sessions = new ArrayList<>();
    /** 프로세스 ID → 이력 */
    private final Map<Long, ProcessSeries> processes = new LinkedHashMap<>();
    /** 서비스명(service.list) → 이력 */
    private final Map<String, ProcessSeries> services = new LinkedHashMap<>();

    public static class ProcessSeries {
        private final List<Double> cpu = new ArrayList<>();
        private final List<Double> mem = new ArrayList<>();
        private String source;

        public List<Double> getCpu() { return cpu; }
        public List<Double> getMem() { return mem; }
        public String getSource() { return source; }
    }

    public void addServerPoint(MetricHist h) {
        String t = new SimpleDateFormat("HH:mm").format(h.getCollectDt());
        times.add(t);
        ts.add(h.getCollectDt().getTime());
        cpu.add(d(h.getCpuPct()));
        mem.add(d(h.getMemPct()));
        disk.add(h.getDiskPct() == null ? null : d(h.getDiskPct()));
        sessions.add(h.getSessionCnt());
        source = h.getSrcCd();
        lastTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(h.getCollectDt());
    }

    public void addProcessPoint(MetricHist h) {
        ProcessSeries s = processes.computeIfAbsent(h.getProcId(), k -> new ProcessSeries());
        s.cpu.add(d(h.getCpuPct()));
        s.mem.add(d(h.getMemPct()));
        s.source = h.getSrcCd();
    }

    public void addServicePoint(com.scb.monitoring.svcctl.domain.ServiceMetricHist h) {
        ProcessSeries s = services.computeIfAbsent(h.getSvcNm(), k -> new ProcessSeries());
        s.cpu.add(d(h.getCpuPct()));
        s.mem.add(d(h.getMemPct()));
        s.source = "AGENT";
    }

    private static Double d(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    public String getSource() { return source; }
    public String getLastTime() { return lastTime; }
    public List<String> getTimes() { return times; }
    public List<Long> getTs() { return ts; }
    public List<Double> getCpu() { return cpu; }
    public List<Double> getMem() { return mem; }
    public List<Double> getDisk() { return disk; }
    public List<Integer> getSessions() { return sessions; }
    public Map<Long, ProcessSeries> getProcesses() { return processes; }
    public Map<String, ProcessSeries> getServices() { return services; }
}
