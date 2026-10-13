package com.scb.monitoring.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.retention.service.RetentionService;
import com.scb.monitoring.topology.service.TopologyException;

/** 로그 · 이력 최장 보관 기간 : 분류별 저장, 지난 행 삭제 (진행 중인 알람 · 명령 · 리포트는 유지) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({RetentionService.class, com.scb.monitoring.ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AgentProperties.class)
class RetentionServiceTest {

    @Autowired RetentionService retention;
    @Autowired JdbcTemplate jdbc;

    private static Timestamp daysAgo(int d) {
        return new Timestamp(System.currentTimeMillis() - d * 86400000L);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void defaultsAndChange() {
        assertEquals(7, retention.keepDays("METRIC"), "수집 이력 기본 = app.agent.history-keep-days");
        assertEquals(30, retention.keepDays("LOG"));

        retention.change("metric", 30, "admin");
        assertEquals(30, retention.keepDays("METRIC"));
        retention.change("LOG", 45, "admin");            // 사용자 정의
        Map<String, Object> v = retention.getView();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cats = (List<Map<String, Object>>) v.get("categories");
        Map<String, Object> log = cats.stream().filter(c -> "LOG".equals(c.get("code"))).findFirst().get();
        assertEquals(45, log.get("keepDays"));
        assertEquals(Boolean.TRUE, log.get("custom"));
        assertEquals("admin", log.get("updUser"));

        assertThrows(TopologyException.class, () -> retention.change("LOG", 0, "admin"));
        assertThrows(TopologyException.class, () -> retention.change("LOG", 3651, "admin"));
        assertThrows(TopologyException.class, () -> retention.change("NOPE", 30, "admin"));

        retention.changeAll(365, "admin");
        for (RetentionService.Category c : retention.getCategories()) assertEquals(365, retention.keepDays(c.code));
    }

    @Test
    void purgeDeletesOnlyExpired() {
        // 수집 이력 : 10일 전 / 1일 전
        jdbc.update("DELETE FROM tb_server_metric_hist");
        jdbc.update("INSERT INTO tb_server_metric_hist (server_id, collect_dt, cpu_pct, mem_pct, src_cd) VALUES (11, ?, 1, 1, 'AGENT')", daysAgo(10));
        jdbc.update("INSERT INTO tb_server_metric_hist (server_id, collect_dt, cpu_pct, mem_pct, src_cd) VALUES (11, ?, 1, 1, 'AGENT')", daysAgo(1));
        // 알람 : 400일 전 해소 / 400일 전 발생했지만 아직 진행 중
        jdbc.update("DELETE FROM tb_alarm");
        jdbc.update("INSERT INTO tb_alarm (server_id, alarm_type, alarm_level, message, state_cd, occur_dt, close_dt) VALUES (11, 'SERVER_DOWN', 'CRITICAL', 'old closed', 'CLOSED', ?, ?)",
                daysAgo(401), daysAgo(400));
        jdbc.update("INSERT INTO tb_alarm (server_id, alarm_type, alarm_level, message, state_cd, occur_dt) VALUES (12, 'SERVER_DOWN', 'CRITICAL', 'still open', 'OPEN', ?)", daysAgo(400));
        // 로그인 이력 : 2년 전
        jdbc.update("INSERT INTO tb_login_hist (user_id, login_dt, client_ip, result_cd) VALUES ('ghost', ?, '10.0.0.1', 'FAIL')", daysAgo(730));

        Map<String, Object> m = retention.purgeNow("admin");
        @SuppressWarnings("unchecked")
        Map<String, Integer> del = (Map<String, Integer>) m.get("deleted");
        assertEquals(1, del.get("tb_server_metric_hist").intValue(), "7일 지난 수집 이력 1건");
        assertEquals(1, count("SELECT COUNT(*) FROM tb_server_metric_hist"));
        assertEquals(1, del.get("tb_alarm").intValue(), "해소 알람만 삭제");
        assertEquals(1, count("SELECT COUNT(*) FROM tb_alarm WHERE state_cd = 'OPEN'"), "진행 중 알람은 유지");
        assertTrue(del.get("tb_login_hist") >= 1);
        assertEquals(0, count("SELECT COUNT(*) FROM tb_login_hist WHERE user_id = 'ghost'"));

        // 보관 기간을 늘리면 남김
        retention.change("METRIC", 30, "admin");
        jdbc.update("INSERT INTO tb_server_metric_hist (server_id, collect_dt, cpu_pct, mem_pct, src_cd) VALUES (11, ?, 1, 1, 'AGENT')", daysAgo(10));
        retention.purgeNow("admin");
        assertEquals(2, count("SELECT COUNT(*) FROM tb_server_metric_hist"), "30일 보관 → 10일 전 기록 유지");
        assertTrue(retention.getView().get("lastPurge") != null);
    }
}
