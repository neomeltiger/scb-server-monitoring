package com.scb.monitoring.web;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.scb.monitoring.config.db.DbCopy;
import com.scb.monitoring.config.db.DbKind;
import com.scb.monitoring.config.db.DbManager;
import com.scb.monitoring.config.db.DbSettings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 : DB 연결 관리 (/admin/db) - 주 DB 전환, DB 별 연결 확인·설정, 자동 교체 설정.
 * 설정은 conf/scb-db.properties 에 저장 (비밀번호는 암호화), 전환은 바로 적용 (웹 재시작 없음).
 */
@Tag(name = "7. 기타", description = "웹 콘솔·DB 상태")
@Controller
public class DbAdminController {

    private static final Logger log = LoggerFactory.getLogger(DbAdminController.class);

    private final DbManager manager;

    public DbAdminController(DbManager manager) {
        this.manager = manager;
    }

    @GetMapping("/admin/db")
    public String page(Model model) {
        model.addAttribute("menu", "dbconn");
        return "db_admin";
    }

    @Operation(summary = "DB 연결 관리 : 지금 주 DB, 우선순위별 연결 상태, 설정, 전환 기록 (ADMIN)")
    @GetMapping("/api/admin/db")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> overview() {
        DbSettings s = manager.getStore().get();
        DbManager.Active a = manager.current();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("key", a.kind.key());
        cur.put("label", a.kind.label());
        cur.put("since", a.since.withNano(0).toString());
        cur.put("reason", a.reason);
        cur.put("health", manager.getLastHealth());
        body.put("current", cur);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("primary", s.getPrimary());
        set.put("failover", s.isFailover());
        List<String> order = new ArrayList<>();
        for (DbKind k : s.getOrder()) order.add(k.key());
        set.put("order", order);
        set.put("healthIntervalSec", s.getHealthIntervalSec());
        set.put("file", manager.getStore().getFile().getAbsolutePath());
        body.put("settings", set);
        body.put("databases", manager.statuses());
        body.put("events", manager.events());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** 연결 시험 요청 : password 를 비우면 저장된 비밀번호로 */
    public static class ConnForm {
        public Boolean enabled;
        public Boolean embedded;
        public String url;
        public String username;
        public String password;
        public boolean clearPassword;
    }

    @Operation(summary = "DB 연결 시험 (저장하지 않음, ADMIN)")
    @PostMapping("/api/admin/db/{kind}/test")
    @ResponseBody
    public Map<String, Object> test(@PathVariable String kind, @RequestBody ConnForm f) {
        DbKind k = kind(kind);
        DbSettings.Conn c = manager.getStore().get().conn(k).copy();
        apply(c, f);
        return manager.test(k, c);
    }

    @Operation(summary = "DB 연결 설정 저장 (conf/scb-db.properties, 비밀번호 암호화, ADMIN)")
    @PutMapping("/api/admin/db/{kind}")
    @ResponseBody
    public Map<String, Object> save(@PathVariable String kind, @RequestBody ConnForm f, Principal user) {
        DbKind k = kind(kind);
        if (k == manager.currentKind() && Boolean.FALSE.equals(f.enabled)) {
            throw new IllegalArgumentException("지금 쓰고 있는 DB 는 끌 수 없습니다. 다른 DB 로 전환한 뒤 끄세요");
        }
        DbSettings.Conn cur = manager.getStore().get().conn(k);
        manager.getStore().saveConn(k, f.enabled == null ? cur.isEnabled() : f.enabled,
                k == DbKind.MARIADB ? f.embedded : null, f.url, f.username, f.password, f.clearPassword);
        log.info("DB 연결 설정 저장 : {} by {}", k.label(), user == null ? "-" : user.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", k.label() + " 연결 설정을 저장했습니다"
                + (k == manager.currentKind() ? " (지금 쓰는 DB 의 접속 정보는 다음 전환·재시작 때 적용)" : ""));
        return m;
    }

    /** 일반 설정 */
    public static class SettingsForm {
        public String primary;
        public Boolean failover;
        public List<String> order;
    }

    @Operation(summary = "주 DB 시작 선택 · 자동 교체 · 우선순위 저장 (ADMIN)")
    @PutMapping("/api/admin/db/settings")
    @ResponseBody
    public Map<String, Object> settings(@RequestBody SettingsForm f, Principal user) {
        List<DbKind> order = null;
        if (f.order != null) {
            order = new ArrayList<>();
            for (String o : f.order) order.add(kind(o));
        }
        if (f.primary != null && !DbSettings.AUTO.equals(f.primary)) kind(f.primary);
        manager.getStore().saveGeneral(f.primary, f.failover, order);
        log.info("DB 설정 저장 : primary={} failover={} order={} by {}", f.primary, f.failover, f.order, user == null ? "-" : user.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "저장했습니다. 자동 교체·우선순위는 바로, 시작 DB 선택은 웹을 다시 시작할 때 적용됩니다");
        return m;
    }

    /** 전환 요청 */
    public static class SwitchForm {
        public boolean copyData;
    }

    @Operation(summary = "주 DB 전환 (copyData 면 지금 DB 데이터를 복사한 뒤 전환, ADMIN)")
    @PostMapping("/api/admin/db/{kind}/switch")
    @ResponseBody
    public Map<String, Object> switchTo(@PathVariable String kind, @RequestBody(required = false) SwitchForm f, Principal user) {
        DbKind k = kind(kind);
        boolean copy = f != null && f.copyData;
        DbManager.SwitchResult r = manager.switchTo(k, copy, user == null ? "-" : user.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", r.from.label() + " → " + r.to.label() + " 전환했습니다" + (copy ? " (데이터 복사)" : ""));
        if (r.copied != null) {
            m.put("copy", DbCopy.summary(r.copied));
            List<String> rows = new ArrayList<>();
            for (DbCopy.Result x : r.copied) if (!x.ok()) rows.add(x.toString().trim());
            m.put("copyProblems", rows);
        }
        return m;
    }

    private static DbKind kind(String key) {
        DbKind k = DbKind.of(key);
        if (k == null) throw new IllegalArgumentException("모르는 DB 입니다 : " + key);
        return k;
    }

    private static void apply(DbSettings.Conn c, ConnForm f) {
        if (f.enabled != null) c.setEnabled(f.enabled);
        if (f.embedded != null) c.setEmbedded(f.embedded);
        if (f.url != null) c.setUrl(f.url.trim().isEmpty() ? null : f.url.trim());
        if (f.username != null) c.setUsername(f.username.trim());
        if (f.clearPassword) c.setPassword(null);
        else if (f.password != null && !f.password.isEmpty()) c.setPassword(f.password);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return message(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> fail(IllegalStateException e) {
        log.warn("DB 연결 관리 : {}", e.getMessage());
        return message(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> message(HttpStatus st, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", msg);
        return ResponseEntity.status(st).body(m);
    }
}
