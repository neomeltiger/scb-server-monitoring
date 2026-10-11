package com.scb.monitoring.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.scb.monitoring.config.SybaseJdbc;

/**
 * Sybase 연결 상태 확인 API (로그인 필요).
 * sybase 프로필이 아니면 enabled=false 를 돌려줍니다.
 */
@RestController
public class DbStatusController {

    private final ObjectProvider<SybaseJdbc> sybase;

    public DbStatusController(ObjectProvider<SybaseJdbc> sybase) {
        this.sybase = sybase;
    }

    @GetMapping("/api/db/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        SybaseJdbc s = sybase.getIfAvailable();
        JdbcTemplate t = s == null ? null : s.getJdbcTemplate();
        if (t == null) {
            body.put("enabled", false);
            body.put("message", "Sybase 연결이 설정되지 않았습니다. (sybase 프로필로 실행하세요)");
        } else {
            body.put("enabled", true);
            long start = System.currentTimeMillis();
            try {
                body.put("database", t.queryForObject("select db_name()", String.class));
                body.put("version", t.queryForObject("select @@version", String.class));
                body.put("connected", true);
            } catch (Exception e) {
                body.put("connected", false);
                body.put("error", rootMessage(e));
            }
            body.put("elapsedMs", System.currentTimeMillis() - start);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
