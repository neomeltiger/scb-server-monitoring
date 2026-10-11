package com.scb.monitoring.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.alarm.service.AlarmService;
import com.scb.monitoring.topology.dto.NodeView;
import com.scb.monitoring.topology.dto.ProcessForm;
import com.scb.monitoring.topology.dto.ProcessView;
import com.scb.monitoring.topology.dto.ServerForm;
import com.scb.monitoring.topology.dto.ServerView;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.topology.service.TopologyService;

/**
 * Service + MyBatis Mapper 통합 테스트.
 * MySQL 대신 H2 메모리 DB 에서 같은 Mapper XML(ANSI SQL)과 같은 초기 데이터(db/data.sql)를 실행합니다.
 * 각 테스트는 트랜잭션 롤백으로 서로 영향을 주지 않습니다.
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({TopologyService.class, AlarmService.class, com.scb.monitoring.ReadOnlyWriteGuard.class, com.scb.monitoring.agent.service.ProcessStatusService.class})
@EnableConfigurationProperties(AgentProperties.class)
class TopologyServiceTest {

    @Autowired TopologyService service;
    @Autowired TopologyMapper mapper;

    private static ServerForm form(String name, String ip, int switchId, String status) {
        ServerForm f = new ServerForm();
        f.setSystemName(name);
        f.setFqdn("test01.example.com");
        f.setOs("SOLARIS");
        f.setIp(ip);
        f.setSwitchNodeId(switchId);
        f.setStatus(status);
        return f;
    }

    private static ServerView find(List<ServerView> list, long id) {
        return list.stream().filter(s -> s.getId() == id).findFirst().orElse(null);
    }

    @Test
    void seedDataMatchesScreen() {
        List<ServerView> list = service.getServers();
        assertEquals(10, list.size());
        assertEquals(27, list.stream().mapToInt(s -> s.getProcesses().size()).sum());

        ServerView ap1 = find(list, 11);
        assertEquals("MUREX MXG AP #1", ap1.getSystemName());
        assertEquals("GASAN MTR-A", ap1.getLocation());
        assertEquals("18.4%", ap1.getCpu());
        assertEquals("52%", ap1.getDisk());
        assertEquals("1,420", ap1.getSessions());
        assertEquals(Long.valueOf(21), ap1.getPairId());
        assertEquals("-", ap1.getProcesses().get(3).getPid());   // mx_batch_sched 중지

        ServerView nfs2 = find(list, 23);
        assertEquals("Down", nfs2.getStatus());
        assertEquals("N/A", nfs2.getDisk());
        assertEquals("DRC", nfs2.getLocation());
    }

    @Test
    void nodesAndSwitches() {
        List<NodeView> nodes = service.getNodes();
        assertEquals(3, nodes.size());
        assertEquals("HUB", nodes.get(0).getType());
        List<NodeView> switches = service.getSwitches();
        assertEquals(2, switches.size());
        assertEquals(Integer.valueOf(10), switches.get(0).getId());
        assertEquals("GASAN MTR-A", switches.get(0).getLocation());
    }

    @Test
    void createServerAddsMetricAndDefaultProcesses() {
        ServerView s = service.createServer(form("MUREX AP #9", "10.0.99.9", 20, "Active"));
        assertTrue(s.getId() >= 100, "신규 서버 ID 는 100 부터");
        assertEquals("DRC", s.getLocation());
        assertEquals("Synchronized (OK)", s.getSync());
        assertEquals("10.5%", s.getCpu());
        assertEquals("40%", s.getDisk());
        assertEquals(2, s.getProcesses().size());
        assertTrue(s.getProcesses().get(0).isRunning());
        assertEquals(11, service.getServers().size());
    }

    @Test
    void duplicateIpIsRejected() {
        TopologyException e = assertThrows(TopologyException.class,
                () -> service.createServer(form("DUP", "10.0.13.217", 10, "Active")));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void invalidSwitchIsRejected() {
        TopologyException e = assertThrows(TopologyException.class,
                () -> service.createServer(form("HUB", "10.0.99.8", 1, "Active")));   // 1 = Hub
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void updateToDownAndBack() {
        ServerView down = service.updateServer(11L, form("MUREX MXG AP #1", "10.0.13.217", 10, "Down"));
        assertEquals("Down", down.getStatus());
        assertEquals("DISCONNECTED (Link Down)", down.getSync());
        assertEquals("0.0%", down.getCpu());
        assertEquals("N/A", down.getDisk());
        assertEquals("0", down.getSessions());

        ServerView up = service.updateServer(11L, form("MUREX MXG AP #1", "10.0.13.217", 20, "Standby"));
        assertEquals("Standby Mirror Ready", up.getSync());
        assertEquals("10.5%", up.getCpu());
        assertEquals("DRC", up.getLocation());
    }

    @Test
    void deleteServerClearsPairAndChildren() {
        service.deleteServer(21L);
        List<ServerView> list = service.getServers();
        assertEquals(9, list.size());
        assertNull(find(list, 21));
        assertNull(find(list, 11).getPairId(), "11 번의 동기화 대상(21) 연결 해제");
        assertTrue(mapper.selectProcessListByServer(21L).isEmpty());
        TopologyException e = assertThrows(TopologyException.class, () -> service.getServer(21L));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void processCrudAndToggle() {
        ProcessForm f = new ProcessForm();
        f.setName("new_daemon");
        f.setPath("/opt/new/bin/daemon");
        f.setRunning(false);
        ProcessView p = service.addProcess(12L, f);
        assertNotNull(p.getId());
        assertFalse(p.isRunning());
        assertEquals("-", p.getPid());
        assertEquals("0.0%", p.getCpu());

        ProcessView on = service.toggleProcess(12L, p.getId());
        assertTrue(on.isRunning());
        assertFalse("-".equals(on.getPid()));

        f.setName("renamed_daemon");
        f.setRunning(false);
        ProcessView off = service.updateProcess(12L, p.getId(), f);
        assertEquals("renamed_daemon", off.getName());
        assertEquals("-", off.getPid());

        service.deleteProcess(12L, p.getId());
        assertEquals(4, service.getServer(12L).getProcesses().size());
        assertThrows(TopologyException.class, () -> service.toggleProcess(11L, p.getId()));
    }

    @Test
    void resetRestoresDefaults() {
        service.deleteServer(11L);
        service.createServer(form("TEMP", "10.0.99.7", 10, "Active"));
        List<ServerView> list = service.resetToDefaults();
        assertEquals(10, list.size());
        assertNotNull(find(list, 11));
        assertEquals(27, list.stream().mapToInt(s -> s.getProcesses().size()).sum());
    }
}
