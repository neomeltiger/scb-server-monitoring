package com.scb.monitoring.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.agent.service.LoadThrottle;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;

/** 서버 부하(CPU/메모리 90% 초과) 시 수집 주기 최소 60초, 그 밖에는 사용자 설정 그대로 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({ServerLogService.class, AgentService.class, ServiceCtlService.class, AlarmService.class,
        com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class,
        com.scb.monitoring.agent.service.HeartbeatService.class, com.scb.monitoring.agent.service.FallbackService.class})
@EnableConfigurationProperties(AgentProperties.class)
class LoadThrottleTest {

    @Autowired AgentService agent;
    @Autowired JdbcTemplate jdbc;

    private static String ctlInterval(String commands) {
        String ctl = commands.split("\n")[0];
        for (String p : ctl.split("\\|")) if (p.startsWith("interval=")) return p.substring(9);
        return null;
    }

    private void metric(String cpu, String mem) {
        jdbc.update("UPDATE tb_server_metric SET cpu_pct = ?, mem_pct = ? WHERE server_id = 11", new BigDecimal(cpu), new BigDecimal(mem));
    }

    @Test
    void ruleOnly() {
        AgentProperties p = new AgentProperties();
        assertEquals(60, LoadThrottle.effectiveInterval(10, new BigDecimal("95"), new BigDecimal("20"), p), "CPU 초과 → 최소 60초");
        assertEquals(60, LoadThrottle.effectiveInterval(30, new BigDecimal("20"), new BigDecimal("90.5"), p), "메모리 초과 → 최소 60초");
        assertEquals(300, LoadThrottle.effectiveInterval(300, new BigDecimal("99"), new BigDecimal("99"), p), "이미 더 길면 그대로");
        assertEquals(10, LoadThrottle.effectiveInterval(10, new BigDecimal("90"), new BigDecimal("90"), p), "90% 는 넘은 것이 아님");
        assertEquals(10, LoadThrottle.effectiveInterval(10, null, null, p), "값이 없으면 사용자 설정");
        assertEquals("CPU 95% · 메모리 91.5%", LoadThrottle.reason(new BigDecimal("95.0"), new BigDecimal("91.5"), p));
        assertNull(LoadThrottle.reason(new BigDecimal("10"), new BigDecimal("10"), p));
    }

    @Test
    void ctlIntervalFollowsLoad() {
        agent.issueToken(11L, "admin");
        agent.changeCollectInterval(11L, 10, "admin");

        metric("20", "40");
        assertEquals("10", ctlInterval(agent.pullCommands(11L, "RUNNING", 1, "1.12")), "평상시 사용자 설정");
        assertFalse(agent.getAgent(11L).isLoadThrottled());

        metric("95.5", "40");
        assertEquals("60", ctlInterval(agent.pullCommands(11L, "RUNNING", 1, "1.12")), "CPU 95.5% → 60초");
        AgentView v = agent.getAgent(11L);
        assertTrue(v.isLoadThrottled());
        assertEquals(10, v.getCollectIntervalSec(), "사용자 설정 값은 그대로");
        assertEquals(60, v.getEffectiveIntervalSec());
        assertTrue(agent.buildConfig(11L).contains("interval=60\n"), "config 도 같은 값");

        metric("30", "96");
        assertEquals("60", ctlInterval(agent.pullCommands(11L, "RUNNING", 1, "1.12")), "메모리 96% → 60초");

        metric("30", "50");
        assertEquals("10", ctlInterval(agent.pullCommands(11L, "RUNNING", 1, "1.12")), "부하가 내려가면 다시 사용자 설정");

        agent.changeCollectInterval(11L, 300, "admin");
        metric("99", "99");
        assertEquals("300", ctlInterval(agent.pullCommands(11L, "RUNNING", 1, "1.12")), "사용자 설정이 더 길면 유지");
    }
}
