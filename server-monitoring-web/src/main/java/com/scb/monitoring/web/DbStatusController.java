package com.scb.monitoring.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.scb.monitoring.config.SybaseJdbc;
import com.scb.monitoring.config.db.DbManager;
import com.scb.monitoring.config.db.H2Jdbc;
import com.scb.monitoring.config.db.MariaDbJdbc;
import com.scb.monitoring.config.db.MysqlJdbc;
import com.scb.monitoring.config.db.NamedJdbc;
import com.scb.monitoring.config.db.OracleJdbc;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DB 연결 상태 (로그인 사용자) : 주 DB (선택 이유·자동 교체) + MySQL / MariaDB / H2 / Sybase / Oracle 개별 연결 확인.
 * 주 DB 전환·연결 설정은 관리자 화면 /admin/db (DbAdminController).
 * 주 DB 가 아닌 임베디드 MariaDB · H2 는 상태 확인만으로 시작·생성하지 않습니다.
 */
@Tag(name = "7. 기타", description = "웹 콘솔·DB 상태")
@RestController
public class DbStatusController {

    private final DbManager manager;
    private final MysqlJdbc mysql;
    private final MariaDbJdbc mariadb;
    private final H2Jdbc h2;
    private final ObjectProvider<SybaseJdbc> sybase;
    private final ObjectProvider<OracleJdbc> oracle;

    public DbStatusController(DbManager manager, MysqlJdbc mysql, MariaDbJdbc mariadb, H2Jdbc h2,
                              ObjectProvider<SybaseJdbc> sybase, ObjectProvider<OracleJdbc> oracle) {
        this.manager = manager;
        this.mysql = mysql;
        this.mariadb = mariadb;
        this.h2 = h2;
        this.sybase = sybase;
        this.oracle = oracle;
    }

    @Operation(summary = "DB 연결 상태 (주 DB 선택 결과 + MySQL / MariaDB / H2 / Sybase / Oracle)")
    @GetMapping("/api/db/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> primary = new LinkedHashMap<>();
        DbManager.Active a = manager.current();
        primary.put("db", a.kind.key());
        primary.put("mode", manager.getStore().get().getPrimary());
        primary.put("failover", manager.getStore().get().isFailover());
        primary.put("since", a.since.withNano(0).toString());
        primary.put("reason", a.reason);
        body.put("primary", primary);

        List<Map<String, Object>> dbs = new ArrayList<>();
        dbs.add(entry("mysql", mysql));
        dbs.add(entry("mariadb", mariadb));
        dbs.add(entry("h2", h2));
        dbs.add(optional("sybase", sybase.getIfAvailable(), "sybase 프로필로 실행하면 연결"));
        dbs.add(optional("oracle", oracle.getIfAvailable(), "oracle 프로필로 실행하면 연결 (SCB_ORACLE_URL / SCB_ORACLE_USER / SCB_ORACLE_PASSWORD)"));
        body.put("databases", dbs);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private Map<String, Object> entry(String key, NamedJdbc j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("enabled", true);
        m.put("primary", key.equals(manager.currentKind().key()));
        m.putAll(j.status());
        return m;
    }

    private Map<String, Object> optional(String key, NamedJdbc j, String hint) {
        if (j != null) return entry(key, j);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("enabled", false);
        m.put("message", hint);
        return m;
    }
}
