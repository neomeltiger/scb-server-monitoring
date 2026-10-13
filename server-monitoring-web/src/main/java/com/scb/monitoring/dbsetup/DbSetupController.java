package com.scb.monitoring.dbsetup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.mapper.UserMapper;
import com.scb.monitoring.config.db.DbDialect;
import com.scb.monitoring.config.db.DbKind;
import com.scb.monitoring.config.db.DbSchema;
import com.scb.monitoring.topology.service.TopologyException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 : DB 초기 구성 (/admin/db-setup) - 신규 DB 에 스키마 · 테이블 · 기초 데이터를 단계별 버튼으로 만듦 (자동 실행 없음).
 * <pre>
 *  들어올 때 관리자 비밀번호를 한 번 더 확인 (POST /api/admin/db-setup/verify) - 10분 유지, 5회 틀리면 5분 잠금
 *  확인 전에는 모든 단계 API 가 403 (세션 속성 dbSetupVerifiedAt)
 * </pre>
 */
@Tag(name = "7. 기타", description = "웹 콘솔·DB 상태")
@Controller
public class DbSetupController {

    private static final Logger log = LoggerFactory.getLogger(DbSetupController.class);

    static final String VERIFIED = "dbSetupVerifiedAt";
    static final String FAILS = "dbSetupFails";
    static final String LOCKED = "dbSetupLockedUntil";
    static final long VALID_MS = 10 * 60 * 1000L;
    static final int MAX_FAILS = 5;
    static final long LOCK_MS = 5 * 60 * 1000L;
    /** 내려받기 · 보기 허용 파일 */
    private static final List<String> FILES = new ArrayList<>();

    static {
        for (DbKind k : new DbKind[] {DbKind.ORACLE, DbKind.SYBASE, DbKind.MYSQL, DbKind.H2}) FILES.add(DbSetupScripts.ddlFile(k));
        FILES.add(DbSetupScripts.baseDataFile());
    }

    private final DbSetupService service;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    /** 웹이 쓰는 주 DB (config.db.SwitchingDataSource) */
    private final javax.sql.DataSource dataSource;

    public DbSetupController(DbSetupService service, UserMapper userMapper, PasswordEncoder passwordEncoder,
                             javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
        this.service = service;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/admin/db-setup")
    public String page(Model model) {
        model.addAttribute("menu", "dbsetup");
        return "db_setup";
    }

    // ===================== 관리자 비밀번호 재확인 =====================

    @Operation(summary = "DB 초기 구성 화면 들어가기 전 관리자 비밀번호 재확인 {password} - ADMIN")
    @PostMapping("/api/admin/db-setup/verify")
    @ResponseBody
    public Map<String, Object> verify(@RequestBody Map<String, Object> body, Authentication auth, HttpSession session) {
        long now = System.currentTimeMillis();
        Long locked = (Long) session.getAttribute(LOCKED);
        if (locked != null && locked > now) {
            throw new TopologyException(HttpStatus.TOO_MANY_REQUESTS,
                    "비밀번호를 " + MAX_FAILS + "번 틀려 " + ((locked - now) / 60000 + 1) + "분 동안 확인할 수 없습니다.");
        }
        Object pw = body.get("password");
        AppUser u = userMapper.selectUser(auth.getName());
        boolean ok = u != null && pw != null && !String.valueOf(pw).isEmpty() && passwordEncoder.matches(String.valueOf(pw), u.getPasswordHash());
        if (!ok) {
            int fails = session.getAttribute(FAILS) == null ? 1 : (Integer) session.getAttribute(FAILS) + 1;
            session.setAttribute(FAILS, fails);
            log.warn("DB SETUP VERIFY FAIL user={} ({}/{})", auth.getName(), fails, MAX_FAILS);
            if (fails >= MAX_FAILS) {
                session.setAttribute(LOCKED, now + LOCK_MS);
                session.removeAttribute(FAILS);
                throw new TopologyException(HttpStatus.TOO_MANY_REQUESTS, "비밀번호를 " + MAX_FAILS + "번 틀렸습니다. 5분 뒤 다시 시도하세요.");
            }
            throw new TopologyException(HttpStatus.UNAUTHORIZED, "비밀번호가 맞지 않습니다. (" + fails + " / " + MAX_FAILS + ")");
        }
        session.removeAttribute(FAILS);
        session.setAttribute(VERIFIED, now);
        log.info("DB SETUP VERIFY OK user={}", auth.getName());
        return status(session);
    }

    @Operation(summary = "비밀번호 확인 상태 (남은 시간)")
    @GetMapping("/api/admin/db-setup/verify")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> verifyStatus(HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status(session));
    }

    private Map<String, Object> status(HttpSession session) {
        Long at = (Long) session.getAttribute(VERIFIED);
        long left = at == null ? 0 : Math.max(0, at + VALID_MS - System.currentTimeMillis());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verified", left > 0);
        m.put("leftSec", left / 1000);
        return m;
    }

    /** 단계 API 공통 : 비밀번호 확인 10분 안이어야 함 */
    private void requireVerified(HttpSession session) {
        if (!Boolean.TRUE.equals(status(session).get("verified"))) {
            throw new TopologyException(HttpStatus.FORBIDDEN, "관리자 비밀번호 확인이 필요합니다 (확인 후 10분 동안 사용 가능).");
        }
    }

    @Operation(summary = "지금 웹이 쓰는 DB 에 스키마 · 테이블 · 기초 데이터가 있는지 (읽기만) - ADMIN")
    @GetMapping("/api/admin/db-setup/current")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> current(HttpSession session) {
        requireVerified(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.checkCurrent(dataSource));
    }

    /**
     * 관리자 화면 공통 점검 (fragments/top.html) : 지금 DB 에 없는 테이블 요약만 - 비밀번호 재확인 없이, 읽기만.
     * 없는 테이블이 있으면 화면이 알림창을 띄우고 [확인] 하면 DB 초기 구성 화면으로 이동
     */
    @Operation(summary = "지금 DB 의 스키마 · 테이블 점검 요약 (관리자 화면 알림용) - ADMIN")
    @GetMapping("/api/admin/db-setup/health")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> health() {
        // 관리자 화면마다 부르므로 60초 동안은 지난 결과를 씀 (DB 메타데이터 조회 줄이기)
        long now = System.currentTimeMillis();
        Map<String, Object> cached = healthCache;
        if (cached != null && now - healthAt < HEALTH_CACHE_MS) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cached);
        }
        Map<String, Object> cur = service.checkCurrent(dataSource);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", cur.get("connected"));
        m.put("label", cur.get("label"));
        m.put("schema", cur.get("schema"));
        m.put("schemaExists", cur.get("schemaExists"));
        m.put("verdict", cur.get("verdict"));
        Object missing = cur.get("missing");
        m.put("missing", missing);
        m.put("missingCount", missing instanceof List ? ((List<?>) missing).size() : 0);
        m.put("targetCount", cur.get("targetCount"));
        healthCache = m;
        healthAt = now;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(m);
    }

    private static final long HEALTH_CACHE_MS = 60_000L;
    private volatile Map<String, Object> healthCache;
    private volatile long healthAt;

    // ===================== 단계 (버튼으로만 실행) =====================

    @Operation(summary = "1. 연결 확인 : 입력한 계정으로 접속, 스키마 · 테이블 · 기초 데이터가 있는지 - ADMIN")
    @PostMapping("/api/admin/db-setup/test")
    @ResponseBody
    public Map<String, Object> test(@RequestBody DbSetupRequest req, HttpSession session) {
        requireVerified(session);
        return service.test(req);
    }

    @Operation(summary = "2. 스키마 생성 : 없을 때만 (있으면 건너뜀) - ADMIN")
    @PostMapping("/api/admin/db-setup/schema")
    @ResponseBody
    public Map<String, Object> schema(@RequestBody DbSetupRequest req, HttpSession session) {
        requireVerified(session);
        return service.createSchema(req);
    }

    @Operation(summary = "3. 테이블 생성 : db/setup/ddl-*.sql, 없는 테이블만 - ADMIN")
    @PostMapping("/api/admin/db-setup/tables")
    @ResponseBody
    public Map<String, Object> tables(@RequestBody DbSetupRequest req, HttpSession session) {
        requireVerified(session);
        return service.createTables(req);
    }

    @Operation(summary = "4. 기초 데이터 : db/setup/base-data.sql (사용자 · 메뉴 · 서버 정보 · 설정, 이력 제외), 기초 테이블이 비었을 때만 - ADMIN")
    @PostMapping("/api/admin/db-setup/data")
    @ResponseBody
    public Map<String, Object> data(@RequestBody DbSetupRequest req, HttpSession session) {
        requireVerified(session);
        return service.insertBaseData(req);
    }

    // ===================== 프로젝트 안 스크립트 =====================

    @Operation(summary = "저장된 DDL · 기초 데이터 파일 목록 (src/main/resources/db/setup)")
    @GetMapping("/api/admin/db-setup/scripts")
    @ResponseBody
    public Map<String, Object> scripts() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String f : FILES) {
            String text = DbSchema.resource(f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.substring(f.lastIndexOf('/') + 1));
            m.put("path", "src/main/resources/" + f);
            m.put("bytes", text.getBytes(StandardCharsets.UTF_8).length);
            m.put("statements", DbDialect.split(text).size());
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", list);
        body.put("tables", service.targetTables());
        body.put("baseTables", DbSetupScripts.baseTables());
        return body;
    }

    @Operation(summary = "저장된 스크립트 내용 (text/plain)")
    @GetMapping(value = "/api/admin/db-setup/scripts/{name:.+}", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> script(@PathVariable("name") String name) {
        for (String f : FILES) {
            if (f.endsWith("/" + name)) {
                return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(DbSchema.resource(f));
            }
        }
        throw TopologyException.notFound("없는 스크립트입니다 : " + name);
    }
}
