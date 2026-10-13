package com.scb.monitoring.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.scb.monitoring.menu.domain.Menu;
import com.scb.monitoring.menu.service.MenuService;
import com.scb.monitoring.topology.service.TopologyException;

/** 권한관리 : 기본 메뉴, 관리자 / 일반 사용자 메뉴, 일반 사용자 권한 저장, 이름·순서·그룹 저장 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({MenuService.class, com.scb.monitoring.ReadOnlyWriteGuard.class})
class MenuServiceTest {

    @Autowired MenuService service;

    private static List<String> ids(List<MenuService.Node> tree) {
        List<String> out = new ArrayList<>();
        for (MenuService.Node n : tree) {
            out.add(n.id);
            n.children.forEach(c -> out.add(c.id));
        }
        return out;
    }

    @Test
    void defaultsAdminSeesAllUserSeesNoAdminMenus() {
        assertEquals(MenuService.DEFAULTS.size(), service.all().size());
        List<String> admin = ids(service.visibleTree(true));
        assertTrue(admin.containsAll(Arrays.asList("main", "g_auth", "users", "menuauth", "menuedit", "g_system", "apiguide", "network")));
        List<String> user = ids(service.visibleTree(false));
        assertTrue(user.containsAll(Arrays.asList("main", "network", "processes", "manual")));
        assertFalse(user.contains("g_auth"));
        assertFalse(user.contains("users"));
        assertFalse(user.contains("g_system"));
        assertEquals("메인 홈 > 권한관리 > 메뉴 권한", service.crumb("menuauth"));
    }

    @Test
    void userAccessOnlySelectedMenusAndAdminOnlyIgnored() {
        service.saveUserAccess(Arrays.asList("network", "users"), "admin");     // users 는 관리자 전용이라 무시
        assertTrue(service.isAllowed(false, "network"));
        assertFalse(service.isAllowed(false, "servers"));
        assertFalse(service.isAllowed(false, "users"));
        assertTrue(service.isAllowed(false, "main"));                           // 메인 홈은 항상
        assertTrue(service.isAllowed(true, "servers"));                         // 관리자는 전체
        List<String> user = ids(service.visibleTree(false));
        assertTrue(user.contains("network"));
        assertFalse(user.contains("g_process"), "허용된 하위 메뉴가 없는 그룹은 숨김");
        assertThrows(TopologyException.class, () -> service.saveUserAccess(Arrays.asList("nope"), "admin"));
    }

    @Test
    void layoutRenameAndMoveBetweenGroups() {
        List<MenuService.LayoutItem> items = new ArrayList<>();
        for (Menu m : service.all()) {
            MenuService.LayoutItem it = new MenuService.LayoutItem();
            it.id = m.getMenuId();
            it.parentId = m.getParentId();
            it.sortOrd = m.getSortOrd();
            it.name = m.getMenuNm();
            if ("logs".equals(it.id)) {         // 서버 로그 → 프로세스운영 그룹 맨 앞, 이름 변경
                it.parentId = "g_process";
                it.sortOrd = 1;
                it.name = "로그 조회";
            }
            items.add(it);
        }
        service.saveLayout(items, "admin");
        MenuService.Node proc = service.visibleTree(true).stream().filter(n -> "g_process".equals(n.id)).findFirst().get();
        assertEquals(Arrays.asList("logs", "processes"), proc.children.stream().map(c -> c.id).collect(Collectors.toList()));
        assertEquals("로그 조회", proc.children.get(0).name);
        assertEquals("메인 홈 > 프로세스운영 > 로그 조회", service.crumb("logs"));

        // 그룹을 다른 그룹 안에 넣거나 이름을 비우면 거부
        items.stream().filter(i -> "g_auth".equals(i.id)).findFirst().get().parentId = "g_system";
        assertThrows(TopologyException.class, () -> service.saveLayout(items, "admin"));

        service.resetDefaults("admin");
        assertEquals("서버 로그", service.all().stream().filter(m -> "logs".equals(m.getMenuId())).findFirst().get().getMenuNm());
    }

    @Autowired javax.sql.DataSource dataSource;

    /** 사용자별 메뉴 권한 : 지정하면 그 메뉴만, 해제하면 기본 권한, 관리자 전용 메뉴는 줄 수 없음 */
    @Test
    void perUserMenus() {
        org.springframework.jdbc.core.JdbcTemplate j = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        j.update("INSERT INTO tb_user (user_id, user_nm, password_hash, role_cd) VALUES ('lee01', '이', 'x', 'USER')");
        assertTrue(service.isAllowedFor("lee01", false, "servers"), "지정 전 = 기본 권한");

        service.saveUserMenus("lee01", true, Arrays.asList("logs", "users"), "admin");
        assertTrue(service.isAllowedFor("lee01", false, "logs"));
        assertFalse(service.isAllowedFor("lee01", false, "servers"));
        assertFalse(service.isAllowedFor("lee01", false, "users"), "관리자 전용은 줄 수 없음");
        assertTrue(service.isAllowedFor("lee01", false, "main"));
        assertEquals(Arrays.asList("main", "g_resource", "logs"), ids(service.visibleTreeFor("lee01", false)));
        assertTrue(service.isAllowedFor("other", false, "servers"), "다른 사용자는 기본 권한");

        service.saveUserMenus("lee01", false, null, "admin");
        assertTrue(service.isAllowedFor("lee01", false, "servers"), "해제하면 기본 권한");
        assertThrows(TopologyException.class, () -> service.saveUserMenus("nobody", true, Arrays.asList("logs"), "admin"));
    }
}
