package com.scb.monitoring.svcctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.scb.monitoring.ReadOnlyWriteGuard;
import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.svcctl.dto.CommandForm;
import com.scb.monitoring.svcctl.dto.CommandView;
import com.scb.monitoring.svcctl.dto.ServiceForm;
import com.scb.monitoring.svcctl.dto.ServiceView;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;

/** 프로세스 관리 : 서비스 추가 / 수정 / 삭제, service.list 문서 생성, 서비스별 제어 이력 (seed: server 11 = 27건) */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({ServiceCtlService.class, ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AgentProperties.class)
class ServiceCrudTest {

    @Autowired ServiceCtlService svc;

    private static ServiceForm form(String name, int group, int level, String script, String... cmds) {
        ServiceForm f = new ServiceForm();
        f.setName(name);
        f.setGroup(group);
        f.setLevel(level);
        f.setAccount("murex");
        f.setScript(script);
        f.setCmds(Arrays.asList(cmds));
        return f;
    }

    private List<ServiceView> list(long serverId) {
        return svc.getServerServices(serverId).getServices();
    }

    @Test
    void addUpdateDeleteKeepsListOrder() {
        ServiceView a = svc.addService(12L, form("APP_A", 2, 2, "APP_A/a.sh", "start", "stop"));
        ServiceView b = svc.addService(12L, form("APP_B", 3, 3, "/opt/x/b.sh", "start", "stop", "kill"));
        ServiceView c = svc.addService(12L, form("APP_C", 4, 4, "APP_C/c.sh", "start"));
        assertEquals(Integer.valueOf(1), a.getNo());
        assertEquals(Integer.valueOf(3), c.getNo());
        assertEquals("/shared/opt/pos/APP_A/a.sh", a.getScript(), "상대 경로는 SCRIPT_HOME 기준");
        assertEquals("/opt/x/b.sh", b.getScript());

        ServiceView b2 = svc.updateService(12L, b.getId(), form("APP_B2", 5, 6, "/opt/x/b2.sh", "start", "stop"));
        assertEquals("APP_B2", b2.getName(), "이름 변경 가능");
        assertEquals(Integer.valueOf(2), b2.getNo(), "순번 유지");
        assertEquals("start|stop", b2.getCmds());

        svc.deleteService(12L, a.getId());
        List<ServiceView> l = list(12L);
        assertEquals(Arrays.asList("APP_B2", "APP_C"), l.stream().map(ServiceView::getName).collect(Collectors.toList()));
        assertEquals(Arrays.asList(1, 2), l.stream().map(ServiceView::getNo).collect(Collectors.toList()), "삭제 후 순번 다시 매김");
    }

    @Test
    void validationIsSameAsServiceList() {
        svc.addService(12L, form("APP_A", 2, 2, "APP_A/a.sh", "start"));
        assertThrows(TopologyException.class, () -> svc.addService(12L, form("APP_A", 2, 2, "x.sh", "start")), "중복 이름");
        assertThrows(TopologyException.class, () -> svc.addService(12L, form("bad name", 2, 2, "x.sh", "start")), "이름 형식");
        assertThrows(TopologyException.class, () -> svc.addService(12L, form("APP_X", 2, 2, "../etc/x.sh", "start")), "경로 ..");
        assertThrows(TopologyException.class, () -> svc.addService(12L, form("APP_X", 2, 2, "x.sh", "rm")), "허용 안 된 명령");
        assertThrows(TopologyException.class, () -> svc.addService(12L, form("APP_X", 2, 2, "x;rm.sh", "start")), "특수문자");
        ServiceView b = svc.addService(12L, form("APP_B", 2, 2, "b.sh", "start"));
        assertThrows(TopologyException.class, () -> svc.updateService(12L, b.getId(), form("APP_A", 2, 2, "b.sh", "start")), "다른 서비스 이름으로 변경");
        assertThrows(TopologyException.class, () -> svc.deleteService(11L, b.getId()), "다른 서버의 서비스");
    }

    @Test
    void exportServiceListRoundTrips() {
        String text = svc.exportServiceList(11L, "/shared/opt/pos");
        List<String> lines = Arrays.stream(text.split("\n")).filter(l -> !l.startsWith("#")).collect(Collectors.toList());
        assertEquals(27, lines.size());
        assertTrue(lines.get(0).startsWith("2|2|SCB_fs_app1|murex|"), lines.get(0));
        assertEquals(7, lines.get(0).split("\\|", 7).length, "GROUP|LEVEL|NAME|ACCOUNT|SCRIPT|ARG|CMDS");
        assertFalse(lines.get(0).split("\\|")[4].startsWith("/"), "SCRIPT_HOME 아래 경로는 상대 경로로");
        assertTrue(text.contains("# GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT|ARG|CMDS"));

        // 만든 문서를 다른 서버에 그대로 가져오면 같은 목록
        com.scb.monitoring.svcctl.dto.ImportForm f = new com.scb.monitoring.svcctl.dto.ImportForm();
        f.setText(text);
        f.setScriptHome("/shared/opt/pos");
        assertEquals(27, svc.importServiceList(12L, f).getAdded());
        assertEquals(list(11L).stream().map(v -> v.getName() + v.getScript() + v.getCmds()).collect(Collectors.toList()),
                list(12L).stream().map(v -> v.getName() + v.getScript() + v.getCmds()).collect(Collectors.toList()));
    }

    @Test
    void serviceCommandHistoryIncludesGroupLevelAll() {
        ServiceView fs = list(11L).get(0);   // SCB_fs_app1 : group 2, level 2
        CommandView own = svc.requestCommand(11L, cmd(fs.getName(), "status"), "admin");
        CommandView grp = svc.requestCommand(11L, cmd("group:" + fs.getGroup(), "status"), "admin");
        CommandView lvl = svc.requestCommand(11L, cmd("level:" + fs.getLevel(), "status"), "admin");
        CommandView all = svc.requestCommand(11L, cmd("all", "status"), "admin");
        int otherGroup = list(11L).stream().map(ServiceView::getGroup).filter(g -> !g.equals(fs.getGroup())).findFirst().get();
        CommandView other = svc.requestCommand(11L, cmd("group:" + otherGroup, "status"), "admin");
        List<Long> ids = svc.getServiceCommands(11L, fs.getId(), 50).stream().map(CommandView::getId).collect(Collectors.toList());
        assertTrue(ids.containsAll(Arrays.asList(own.getId(), grp.getId(), lvl.getId(), all.getId())));
        assertFalse(ids.contains(other.getId()), "관계없는 그룹은 제외");
        assertEquals(5, svc.getAllCommands(11L, 100).size());
    }

    private static CommandForm cmd(String target, String c) {
        CommandForm f = new CommandForm();
        f.setTarget(target);
        f.setCmd(c);
        return f;
    }
}
