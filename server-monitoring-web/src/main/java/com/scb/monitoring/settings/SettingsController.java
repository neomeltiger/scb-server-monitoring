package com.scb.monitoring.settings;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.retention.service.RetentionService;
import com.scb.monitoring.serverlog.domain.LogPolicy;
import com.scb.monitoring.serverlog.mapper.ServerLogMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 : 환경 설정 (/admin/settings) - 흩어져 있던 설정을 한 화면에.
 * <pre>
 *  서버별 : 에이전트 토큰(발급은 네트워크 구성도), 하트비트 주기, 수집 주기(부하 시 자동 연장), 서버 로그 요청 주기
 *           → 바꾸기는 기존 API (/api/agents/{id}/heartbeat-interval, /collect-interval, /api/logs/{id}/policy)
 *  DB 연결 : 요약 + DB 연결 관리 화면(/admin/db) 링크
 *  애플리케이션 설정 : application.yml 의 현재 값 (읽기 전용 - 바꾸는 곳 안내), 비밀번호는 감춤
 *  설정 파일 : 웹 서버 · 서버(에이전트) 쪽 파일 위치와 용도
 *  로그 · 이력 보관 기간 : 분류별 1주일 / 1개월 / 6개월 / 1년 / 사용자 정의 → 지난 행 자동 삭제 (retention.service.RetentionService)
 * </pre>
 */
@Tag(name = "7. 기타", description = "웹 콘솔·DB 상태")
@Controller
public class SettingsController {

    private final Environment env;
    private final AgentProperties agentProps;
    private final TopologyMapper topologyMapper;
    private final ServerLogMapper logMapper;
    private final RetentionService retention;

    public SettingsController(Environment env, AgentProperties agentProps, TopologyMapper topologyMapper, ServerLogMapper logMapper,
                              RetentionService retention) {
        this.retention = retention;
        this.env = env;
        this.agentProps = agentProps;
        this.topologyMapper = topologyMapper;
        this.logMapper = logMapper;
    }

    @GetMapping("/admin/settings")
    public String page(Model model) {
        model.addAttribute("menu", "settings");
        return "settings";
    }

    @Operation(summary = "환경 설정 요약 : 서버별 로그 요청 주기, 선택 값 목록, 부하 기준, application.yml 현재 값, 설정 파일 (ADMIN)")
    @GetMapping("/api/admin/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> body = new LinkedHashMap<>();

        // ---- 서버별 로그 요청 주기 (행이 없으면 기본 5분)
        Map<String, Integer> logMin = new LinkedHashMap<>();
        for (Server s : topologyMapper.selectServerList()) {
            LogPolicy p = logMapper.selectPolicy(s.getServerId());
            logMin.put(String.valueOf(s.getServerId()), p == null ? LogPolicy.DEFAULT_INTERVAL_MIN : p.getIntervalMin());
        }
        body.put("logIntervalMin", logMin);

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("collect", Agent.COLLECT_INTERVALS);
        opts.put("heartbeat", Agent.HB_INTERVALS);
        opts.put("log", LogPolicy.INTERVALS);
        body.put("options", opts);

        Map<String, Object> load = new LinkedHashMap<>();
        load.put("pct", agentProps.getLoadThrottlePct());
        load.put("minSec", agentProps.getLoadThrottleMinSec());
        body.put("load", load);

        body.put("app", appSettings());
        body.put("files", files());

        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("profiles", Arrays.asList(env.getActiveProfiles()));
        rt.put("workDir", new File(".").getAbsoluteFile().getParentFile().getAbsolutePath());
        rt.put("java", System.getProperty("java.version"));
        rt.put("startedAt", new java.sql.Timestamp(ManagementFactory.getRuntimeMXBean().getStartTime()).toString().replaceAll("\\.\\d+$", ""));
        body.put("runtime", rt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // ===================== 로그 · 이력 보관 기간 =====================

    @Operation(summary = "로그 · 이력 보관 기간 : 분류별 보관 일수, 대상 테이블 행 수 · 가장 오래된 일시, 마지막 정리 (ADMIN)")
    @GetMapping("/api/admin/retention")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> retention() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(retention.getView());
    }

    @Operation(summary = "분류 하나의 보관 일수 변경 {days} (1 ~ 3650) - ADMIN")
    @PutMapping("/api/admin/retention/{code}")
    @ResponseBody
    public Map<String, Object> retentionChange(@PathVariable("code") String code, @RequestBody Map<String, Object> body, Authentication auth) {
        return retention.change(code, days(body), auth.getName());
    }

    @Operation(summary = "모든 분류의 보관 일수를 한 번에 {days} - ADMIN")
    @PutMapping("/api/admin/retention")
    @ResponseBody
    public Map<String, Object> retentionAll(@RequestBody Map<String, Object> body, Authentication auth) {
        return retention.changeAll(days(body), auth.getName());
    }

    @Operation(summary = "지금 정리 : 보관 기간이 지난 행 삭제 - ADMIN")
    @PostMapping("/api/admin/retention/purge")
    @ResponseBody
    public Map<String, Object> retentionPurge(Authentication auth) {
        Map<String, Object> m = retention.purgeNow(auth.getName());
        Map<String, Object> body = retention.getView();
        body.put("purged", m);
        return body;
    }

    private static int days(Map<String, Object> body) {
        try {
            return Integer.parseInt(String.valueOf(body.get("days")).trim());
        } catch (NumberFormatException e) {
            throw TopologyException.badRequest("보관 기간(일) 값이 올바르지 않습니다.");
        }
    }

    // ===================== application.yml 현재 값 =====================

    /** {group, key, value, env, desc} - 비밀번호는 값 대신 설정 여부만 */
    private List<Map<String, Object>> appSettings() {
        List<Map<String, Object>> out = new ArrayList<>();
        String g = "웹 서버";
        add(out, g, "server.port", "SERVER_PORT", "웹 포트 (에이전트 client.env 의 SCB_WEB_URL 포트와 같아야 함)");
        add(out, g, "server.servlet.session.timeout", null, "로그인 세션 유지 시간");
        add(out, g, "spring.profiles.active", "SPRING_PROFILES", "프로필 (default / demo / prod / sybase / oracle)");
        add(out, g, "app.api-docs.enabled", "API_DOCS_ENABLED", "API 문서 (Swagger · API 가이드) 사용 - 운영은 false");

        g = "에이전트 연동";
        add(out, g, "app.agent.public-url", "SCB_PUBLIC_URL", "에이전트가 접속할 웹 주소 (토큰 발급 화면에 표시, 비우면 화면을 연 주소)");
        add(out, g, "app.agent.report-interval-sec", null, "에이전트 기본 보고 주기 (초, 서버별 수집 주기를 정하기 전)");
        add(out, g, "app.agent.stale-after-sec", null, "이 시간 이상 보고가 없으면 '보고 끊김' (초)");
        add(out, g, "app.agent.heartbeat-check-ms", null, "하트비트 감시 주기 (밀리초) - 3회 연속 미수신이면 서버 Down");
        add(out, g, "app.agent.history-keep-days", null, "수집 이력 보관 일수 기본값 (환경 설정 5. 보관 기간을 저장하면 그 값을 씀)");
        add(out, g, "app.agent.command-request-timeout-min", null, "에이전트가 가져가지 않은 제어 명령 만료 (분)");
        add(out, g, "app.agent.command-result-timeout-min", null, "결과가 오지 않은 제어 명령 만료 (분)");
        add(out, g, "app.agent.load-throttle-pct", "SCB_LOAD_THROTTLE_PCT", "서버 부하 기준 (%) - CPU 또는 메모리가 넘으면 수집 주기 연장");
        add(out, g, "app.agent.load-throttle-min-sec", "SCB_LOAD_THROTTLE_MIN_SEC", "부하일 때 수집 주기 최소값 (초)");

        g = "로그 · 이력 보관";
        add(out, g, "app.retention.purge-interval-ms", null, "보관 기간 지난 행 자동 삭제 주기 (밀리초, 기본 3600000 = 1시간)");
        add(out, g, "app.retention.initial-delay-ms", null, "웹 시작 후 첫 자동 삭제 (밀리초, 기본 120000)");

        g = "장애 알람";
        add(out, g, "app.alarm.check-interval-ms", null, "알람 점검 주기 (밀리초)");
        add(out, g, "app.alarm.initial-delay-ms", null, "웹 시작 후 첫 점검 (밀리초)");
        add(out, g, "app.alarm.closed-keep-hours", null, "메인 홈 '최근 해소 알람' 표시 시간");

        g = "보안";
        add(out, g, "app.security.max-login-failures", null, "연속 로그인 실패 시 계정 잠금 횟수");

        g = "웹 콘솔 (SSH)";
        add(out, g, "app.console.mode", null, "live = 실제 SSH 접속 / demo = 시뮬레이션");
        add(out, g, "app.console.allowed-hosts-file", null, "접속 허용 서버 목록 파일 (실행 위치 기준)");
        add(out, g, "app.console.ssh.connect-timeout-ms", null, "SSH 연결 제한 시간 (밀리초)");
        add(out, g, "app.console.ssh.max-password-tries", null, "비밀번호 시도 횟수");
        add(out, g, "app.console.ssh.strict-host-key-checking", null, "yes = known-hosts 에 등록된 서버만 (운영 권장)");
        add(out, g, "app.console.ssh.legacy-algorithms", null, "구형 SSH(Solaris 10) 호환 알고리즘 허용");
        add(out, g, "app.console.ssh.term-type", null, "터미널 종류 (Solaris 10 은 xterm)");

        g = "DB (기본 연결 · 상세는 DB 연결 관리)";
        add(out, g, "app.db.primary", "SCB_DB", "주 DB (auto / oracle / sybase / mysql / mariadb / h2) - conf/scb-db.properties 의 db.primary 가 우선");
        add(out, g, "app.db.config-file", "SCB_DB_CONFIG", "DB 선택 설정 파일 (비우면 conf/scb-db.properties)");
        add(out, g, "app.db.probe-timeout-ms", "SCB_DB_PROBE_MS", "DB 연결 확인 제한 시간 (밀리초)");
        add(out, g, "app.db.mysql.url", "MYSQL_HOST / MYSQL_PORT", "MySQL 접속 주소");
        add(out, g, "app.db.mysql.username", "MYSQL_USER", "MySQL 사용자");
        add(out, g, "app.db.mysql.password", "MYSQL_PASSWORD", "MySQL 비밀번호");
        add(out, g, "app.db.embedded.enabled", "SCB_MARIADB_EMBEDDED", "임베디드 MariaDB 사용 (Windows)");
        add(out, g, "app.db.embedded.port", "SCB_MARIADB_PORT", "임베디드 MariaDB 포트 (127.0.0.1 만)");
        add(out, g, "app.db.h2.enabled", "SCB_H2_ENABLED", "H2 파일 DB 사용 (마지막 순위)");
        return out;
    }

    private void add(List<Map<String, Object>> out, String group, String key, String envName, String desc) {
        String v = env.getProperty(key);
        if (key.endsWith("password")) v = v == null || v.isEmpty() ? "(비어 있음)" : "●●●● (설정됨)";
        if ("spring.profiles.active".equals(key) && (v == null || v.isEmpty())) v = String.join(",", env.getActiveProfiles());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", group);
        m.put("key", key);
        m.put("value", v == null || v.isEmpty() ? "(비어 있음)" : v);
        m.put("env", envName);
        m.put("desc", desc);
        out.add(m);
    }

    // ===================== 설정 파일 =====================

    private List<Map<String, Object>> files() {
        List<Map<String, Object>> out = new ArrayList<>();
        String w = "웹 서버 (실행 패키지 scb_monitor_web)";
        file(out, w, "conf/scb_web.env", "포트, 프로필, API 문서, 주 DB(SCB_DB), MySQL 접속, 웹 주소(SCB_PUBLIC_URL), 부하 기준, JVM 옵션",
                "메모장 / vi 로 수정 후 bin/scb_web.bat(.sh) restart", true);
        file(out, w, "conf/application.yml", "application.yml 값 덮어쓰기 (app.agent.* · app.alarm.* · app.console.* 등 원하는 키만)",
                "수정 후 웹 다시 시작", true);
        file(out, w, "conf/scb-db.properties", "DB 우선순위 · 자동 교체 · DB 별 접속 정보 (비밀번호 ENC 암호화)",
                "DB 연결 관리 화면에서 저장 (직접 수정도 가능)", true);
        file(out, w, "conf/scb-db.key", "scb-db.properties 비밀번호 암호화 키 - 외부로 보내지 말 것 (압축 제외)", "자동 생성", true);
        file(out, w, "conf/allowed-hosts.json", "웹 콘솔(SSH) 접속 허용 서버 목록", "수정 후 웹 다시 시작", true);
        file(out, w, "lib/scb_monitor_web.jar 안 application.yml", "모든 기본값 (위 파일들이 덮어씀)", "소스 수정 후 빌드", false);
        String a = "서버 (Solaris 10, /shared/opt/agent)";
        file(out, a, "svr_collect_client/etc/client.env", "웹 주소(SCB_WEB_URL), 토큰(SCB_AGENT_TOKEN), 서버 로그 허용 경로(SCB_LOG_ALLOW), 점검 리포트 크기 등",
                "vi 로 수정 (권한 600) → bin/scb_client.sh restart. 토큰은 [토큰 발급] 값", false);
        file(out, a, "svr_opt_agent/etc/scb.env", "서버 제어 에이전트 설정 (pos 경로, 서비스 계정 su 등)", "vi 로 수정", false);
        file(out, a, "svr_opt_agent/etc/service.list", "제어 대상 서비스 목록 (그룹 · 레벨 · 계정 · 스크립트 · 명령)",
                "bin/scb_edit.sh 또는 bin/scb_main.sh sync → 웹 [service.list 가져오기]", false);
        String d = "웹 DB (화면에서 바꾸는 값)";
        file(out, d, "tb_agent (서버별)", "수집 시작/중지, 수집 주기, 하트비트 주기, 토큰(해시)", "이 화면 · 에이전트 관리 · 네트워크 구성도", false);
        file(out, d, "tb_log_policy / tb_log_source", "서버 로그 요청 주기, 수집할 로그 파일", "이 화면 · 서버 로그", false);
        file(out, d, "tb_retention", "로그 · 이력 분류별 최장 보관 기간 (지난 행 자동 삭제)", "이 화면 5. 로그 · 이력 보관 기간", false);
        file(out, d, "tb_menu / tb_user_menu", "메뉴 이름·순서, 메뉴 권한, 사용자별 메뉴", "권한관리 › 메뉴 권한 / 메뉴 이름·순서", false);
        return out;
    }

    private void file(List<Map<String, Object>> out, String where, String name, String desc, String how, boolean checkLocal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("where", where);
        m.put("name", name);
        m.put("desc", desc);
        m.put("how", how);
        if (checkLocal) {
            File f = new File(name);
            m.put("exists", f.isFile());
            m.put("path", f.getAbsolutePath());
        }
        out.add(m);
    }
}
