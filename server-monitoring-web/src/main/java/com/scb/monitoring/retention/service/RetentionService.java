package com.scb.monitoring.retention.service;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.retention.domain.Retention;
import com.scb.monitoring.retention.domain.RetentionStat;
import com.scb.monitoring.retention.mapper.RetentionMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 로그 · 이력 최장 보관 기간 (DB 저장 용량 초과 방지).
 * <pre>
 *  환경 설정 화면에서 분류별로 1주일 / 1개월 / 6개월 / 1년 / 사용자 정의(1 ~ 3650일)를 고르면 tb_retention 에 저장하고,
 *  app.retention.purge-interval-ms (기본 1시간) 마다 보관 기간이 지난 행을 삭제합니다. [지금 정리] 로 바로 삭제도 가능.
 *  진행 중인 것(해소 안 된 알람, 처리 중인 제어 명령, 진행 중인 점검 리포트)은 지우지 않습니다.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** 선택 값 (일) : 1주일 / 1개월 / 6개월 / 1년 - 그 밖은 사용자 정의 */
    public static final List<Integer> PRESETS = Collections.unmodifiableList(Arrays.asList(7, 30, 180, 365));
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 3650;

    /** 정리 대상 테이블 하나 */
    public static final class Target {
        public final String table, dtCol, cond, label;

        Target(String table, String dtCol, String cond, String label) {
            this.table = table;
            this.dtCol = dtCol;
            this.cond = cond;
            this.label = label;
        }
    }

    /** 분류 */
    public static final class Category {
        public final String code, name, desc;
        final int defaultDays;
        public final List<Target> targets;

        Category(String code, String name, String desc, int defaultDays, Target... targets) {
            this.code = code;
            this.name = name;
            this.desc = desc;
            this.defaultDays = defaultDays;
            this.targets = Arrays.asList(targets);
        }
    }

    private final RetentionMapper mapper;
    private final List<Category> categories;
    private final long purgeIntervalMs;
    private volatile Map<String, Object> lastPurge;

    public RetentionService(RetentionMapper mapper, AgentProperties agentProps,
                            @Value("${app.retention.purge-interval-ms:3600000}") long purgeIntervalMs) {
        this.mapper = mapper;
        this.purgeIntervalMs = purgeIntervalMs;
        this.categories = Collections.unmodifiableList(Arrays.asList(
                new Category("METRIC", "자원 · 프로세스 · 서비스 수집 이력", "메인 홈 · 대시보드 그래프의 원본 (서버 수 x 수집 주기마다 쌓여 가장 빨리 늘어남)",
                        agentProps.getHistoryKeepDays(),
                        new Target("tb_server_metric_hist", "collect_dt", null, "서버 자원 이력"),
                        new Target("tb_process_metric_hist", "collect_dt", null, "프로세스 이력"),
                        new Target("tb_service_metric_hist", "collect_dt", null, "서비스 이력")),
                new Category("LOG", "서버 로그 수집본", "서버 로그 화면의 수집 결과 (로그별 최근 20회도 함께 유지)", 30,
                        new Target("tb_log_snapshot", "collect_dt", null, "서버 로그 수집본")),
                new Category("HEARTBEAT", "하트비트 이벤트 이력", "수신 시작 · 끊김 · 복구 · 데몬 중단 이벤트", 180,
                        new Target("tb_heartbeat_hist", "event_dt", null, "하트비트 이벤트")),
                new Category("ALARM", "해소된 장애 알람", "해소(CLOSED)된 알람과 조치 기록 - 진행 중 알람은 지우지 않음", 365,
                        new Target("tb_alarm", "close_dt", "state_cd = 'CLOSED'", "해소 알람")),
                new Category("COMMAND", "서비스 제어 명령 이력", "끝난(완료 · 실패 · 만료) 제어 명령과 결과 - 처리 중 명령은 지우지 않음", 180,
                        new Target("tb_service_cmd", "req_dt", "state_cd IN ('DONE', 'FAILED', 'EXPIRED')", "제어 명령")),
                new Category("LOGIN", "로그인 이력", "로그인 성공 · 실패 기록", 365,
                        new Target("tb_login_hist", "login_dt", null, "로그인 이력")),
                new Category("REPORT", "서버 점검 리포트", "끝난 점검 리포트 (서버별 최근 20건도 함께 유지) - 진행 중 리포트는 지우지 않음", 180,
                        new Target("tb_server_report", "req_dt", "status_cd IN ('DONE', 'ERROR')", "점검 리포트"))));
    }

    public List<Category> getCategories() { return categories; }

    // ===================== 조회 =====================

    /** 분류별 보관 일수 (저장된 값, 없으면 기본값) */
    public int keepDays(String code) {
        Category c = find(code);
        Retention r = mapper.selectOne(c.code);
        return r == null ? c.defaultDays : r.getKeepDays();
    }

    /** 다른 서비스가 매퍼만으로 보관 일수를 읽을 때 (테스트 구성을 단순하게) - 저장값이 없으면 dflt */
    public static int keepDays(RetentionMapper mapper, String code, int dflt) {
        Retention r = mapper.selectOne(code);
        return r == null ? dflt : r.getKeepDays();
    }

    /** 환경 설정 화면 : 분류별 보관 일수 · 대상 테이블 행 수 · 가장 오래된 일시 · 마지막 정리 */
    public Map<String, Object> getView() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Map<String, Retention> saved = new LinkedHashMap<>();
        for (Retention r : mapper.selectAll()) saved.put(r.getCategoryCd(), r);
        List<Map<String, Object>> list = new ArrayList<>();
        long totalRows = 0;
        for (Category c : categories) {
            Retention r = saved.get(c.code);
            int days = r == null ? c.defaultDays : r.getKeepDays();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code);
            m.put("name", c.name);
            m.put("desc", c.desc);
            m.put("keepDays", days);
            m.put("defaultDays", c.defaultDays);
            m.put("custom", !PRESETS.contains(days));
            m.put("updUser", r == null ? null : r.getUpdUser());
            m.put("updDt", r == null || r.getUpdDt() == null ? null : f.format(r.getUpdDt()));
            List<Map<String, Object>> tables = new ArrayList<>();
            long rows = 0;
            for (Target t : c.targets) {
                RetentionStat st = mapper.selectStat(t.table, t.dtCol, t.cond);
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("table", t.table);
                tm.put("label", t.label);
                long n = st == null || st.getCnt() == null ? 0 : st.getCnt();
                tm.put("rows", n);
                tm.put("oldest", st == null || st.getOldest() == null ? null : f.format(st.getOldest()));
                tables.add(tm);
                rows += n;
            }
            m.put("tables", tables);
            m.put("rows", rows);
            totalRows += rows;
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categories", list);
        body.put("presets", PRESETS);
        body.put("minDays", MIN_DAYS);
        body.put("maxDays", MAX_DAYS);
        body.put("totalRows", totalRows);
        body.put("purgeIntervalMin", purgeIntervalMs / 60000);
        body.put("lastPurge", lastPurge);
        return body;
    }

    // ===================== 변경 =====================

    @Transactional
    public Map<String, Object> change(String code, int days, String user) {
        String c = find(code).code;
        save(c, days, user);
        log.info("RETENTION {} = {}일 by {}", code, days, user);
        return getView();
    }

    /** 모든 분류를 같은 기간으로 */
    @Transactional
    public Map<String, Object> changeAll(int days, String user) {
        for (Category c : categories) save(c.code, days, user);
        log.info("RETENTION ALL = {}일 by {}", days, user);
        return getView();
    }

    private void save(String code, int days, String user) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw TopologyException.badRequest("보관 기간은 " + MIN_DAYS + " ~ " + MAX_DAYS + "일 사이로 정하세요.");
        }
        if (mapper.update(code, days, user) == 0) mapper.insert(code, days, user);
    }

    // ===================== 정리 =====================

    /** [지금 정리] - 보관 기간이 지난 행 삭제, 테이블별 삭제 건수 */
    @Transactional
    public Map<String, Object> purgeNow(String user) {
        return doPurge(user);
    }

    /** 주기 정리 (기본 1시간마다, 웹 시작 2분 뒤 첫 실행) */
    @Scheduled(initialDelayString = "${app.retention.initial-delay-ms:120000}", fixedDelayString = "${app.retention.purge-interval-ms:3600000}")
    @Transactional
    public void scheduledPurge() {
        try {
            doPurge("자동");
        } catch (RuntimeException e) {
            log.warn("RETENTION 자동 정리 실패 : {}", e.toString());
        }
    }

    private Map<String, Object> doPurge(String by) {
        long t0 = System.currentTimeMillis();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        int total = 0;
        for (Category c : categories) {
            int days = keepDays(c.code);
            Timestamp before = new Timestamp(t0 - days * 86400000L);
            for (Target t : c.targets) {
                int n = mapper.deleteBefore(t.table, t.dtCol, t.cond, before);
                deleted.put(t.table, n);
                total += n;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(t0)));
        m.put("by", by);
        m.put("total", total);
        m.put("deleted", deleted);
        m.put("ms", System.currentTimeMillis() - t0);
        lastPurge = m;
        if (total > 0 || !"자동".equals(by)) log.info("RETENTION PURGE by {} : {}건 삭제 {}", by, total, deleted);
        return m;
    }

    private Category find(String code) {
        for (Category c : categories) if (c.code.equalsIgnoreCase(code == null ? "" : code)) return c;
        throw TopologyException.notFound("알 수 없는 보관 분류입니다 : " + code);
    }
}
