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

    /** 하위 메뉴(아이콘 없음)를 맨 위 단계로 옮기면 기본 아이콘, 다시 그룹 안으로 넣으면 아이콘 없음 */
    @Test
    void movedToTopGetsDefaultIcon() {
        List<MenuService.LayoutItem> items = new ArrayList<>();
        for (Menu m : service.all()) {
            MenuService.LayoutItem it = new MenuService.LayoutItem();
            it.id = m.getMenuId();
            it.parentId = m.getParentId();
            it.sortOrd = m.getSortOrd();
            it.name = m.getMenuNm();
            if ("logs".equals(it.id) || "swagger".equals(it.id)) {     // 화면 / 새 창 링크를 맨 위로
                it.parentId = null;
                it.sortOrd = "logs".equals(it.id) ? 15 : 55;
            }
            items.add(it);
        }
        service.saveLayout(items, "admin");
        List<MenuService.Node> top = service.visibleTree(true);
        assertEquals("📄", top.stream().filter(n -> "logs".equals(n.id)).findFirst().get().icon);
        assertEquals("🔗", top.stream().filter(n -> "swagger".equals(n.id)).findFirst().get().icon);
        assertEquals("🏠", top.stream().filter(n -> "main".equals(n.id)).findFirst().get().icon, "지정한 아이콘은 그대로");
        assertEquals(null, service.all().stream().filter(m -> "logs".equals(m.getMenuId())).findFirst().get().getIcon(), "DB 에는 저장하지 않음");

        service.resetDefaults("admin");
        MenuService.Node res = service.visibleTree(true).stream().filter(n -> "g_resource".equals(n.id)).findFirst().get();
        assertEquals(null, res.children.stream().filter(n -> "logs".equals(n.id)).findFirst().get().icon, "그룹 안에서는 아이콘 없음");
    }

    private List<MenuService.LayoutItem> layout(String id, String icon) {
        List<MenuService.LayoutItem> items = new ArrayList<>();
        for (Menu m : service.all()) {
            MenuService.LayoutItem it = new MenuService.LayoutItem();
            it.id = m.getMenuId();
            it.parentId = m.getParentId();
            it.sortOrd = m.getSortOrd();
            it.name = m.getMenuNm();
            if (id.equals(it.id)) it.icon = icon;                    // 나머지는 null = 그대로
            items.add(it);
        }
        return items;
    }

    private String iconOf(String id) {
        return service.all().stream().filter(m -> id.equals(m.getMenuId())).findFirst().get().getIcon();
    }

    /** 아이콘 선택 : 이모지 · 이미지 양식(i:id) · 기본(빈 값), 잘못된 값 거부, 보내지 않으면 그대로 */
    @Test
    void chooseIcon() {
        service.saveLayout(layout("logs", "🔍"), "admin");
        assertEquals("🔍", iconOf("logs"));
        assertEquals("🏠", iconOf("main"), "보내지 않은 아이콘은 그대로");
        MenuService.Node res = service.visibleTree(true).stream().filter(n -> "g_resource".equals(n.id)).findFirst().get();
        assertEquals("🔍", res.children.stream().filter(n -> "logs".equals(n.id)).findFirst().get().icon, "하위 메뉴도 고른 아이콘");

        service.saveLayout(layout("main", "i:homef"), "admin");
        MenuService.Node main = service.visibleTree(true).stream().filter(n -> "main".equals(n.id)).findFirst().get();
        assertEquals("homef", main.image, "이미지 양식");

        assertThrows(TopologyException.class, () -> service.saveLayout(layout("main", "i:nope"), "admin"));
        assertThrows(TopologyException.class, () -> service.saveLayout(layout("main", "<b>"), "admin"));
        assertThrows(TopologyException.class, () -> service.saveLayout(layout("main", "ABCDE"), "admin"), "5글자");

        service.saveLayout(layout("logs", ""), "admin");
        assertEquals(null, iconOf("logs"), "기본 = 아이콘 없음");
        assertEquals("🏠", MenuService.defaultIconOf("main"));
        service.resetDefaults("admin");
        assertEquals("🏠", iconOf("main"), "처음 상태로 되돌리면 처음 아이콘");
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
