package com.scb.monitoring.menu.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.menu.domain.Menu;
import com.scb.monitoring.menu.mapper.MenuMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 좌측 메뉴 · 메뉴 권한 (권한관리).
 * <ul>
 *   <li>관리자(ADMIN) : 모든 메뉴·화면 접근</li>
 *   <li>일반 사용자(USER) : 관리자가 [메뉴 권한] 화면에서 고른 메뉴만 (메인 홈은 항상, 권한관리·시스템관리는 관리자 전용 고정)</li>
 *   <li>메뉴 이름 · 순서 · 그룹 이동 : [메뉴 관리] 화면 (끌어서 놓기)</li>
 *   <li>tb_menu 가 없으면 시작할 때 만들고, 코드의 기본 메뉴 중 없는 것만 넣음 (관리자가 바꾼 이름·순서·권한은 유지)</li>
 *   <li>메뉴는 메모리에 두고 바꿀 때마다 다시 읽음 (좌측 메뉴 : fragments/left.html 이 ${@menuService...} 로 호출)</li>
 * </ul>
 */
@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    public static final String MAIN = "main";

    /** 기본 메뉴 (처음 설치 / [기본값으로] 되돌리기) */
    public static final List<Menu> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
            new Menu(MAIN, null, Menu.PAGE, "메인 홈", "/", "🏠", 10, true, false, false),
            new Menu("g_resource", null, Menu.GROUP, "서버자원모니터링", null, "💻", 20, true, false, false),
            new Menu("network", "g_resource", Menu.PAGE, "네트워크구성도", "/network", null, 10, true, false, false),
            new Menu("servers", "g_resource", Menu.PAGE, "서버전체목록", "/servers", null, 20, true, false, false),
            new Menu("logs", "g_resource", Menu.PAGE, "서버 로그", "/logs", null, 30, true, false, false),
            new Menu("heartbeat", "g_resource", Menu.PAGE, "하트비트 모니터링", "/heartbeat", null, 40, true, false, false),
            new Menu("fallback", "g_resource", Menu.PAGE, "데몬 중단 프로세스 현황", "/fallback", null, 50, true, false, false),
            new Menu("agents", "g_resource", Menu.PAGE, "에이전트 관리", "/agents", null, 60, true, false, false),
            new Menu("g_process", null, Menu.GROUP, "프로세스운영", null, "⚙️", 30, true, false, false),
            new Menu("processes", "g_process", Menu.PAGE, "프로세스관리(CRUD)", "/processes", null, 10, true, false, false),
            new Menu("g_auth", null, Menu.GROUP, "권한관리", null, "🔐", 40, false, true, false),
            new Menu("users", "g_auth", Menu.PAGE, "사용자 관리(가입 승인)", "/admin/users", null, 10, false, true, false),
            new Menu("menuauth", "g_auth", Menu.PAGE, "메뉴 권한", "/admin/menu-auth", null, 20, false, true, false),
            new Menu("menuedit", "g_auth", Menu.PAGE, "메뉴 이름·순서", "/admin/menus", null, 30, false, true, false),
            new Menu("g_system", null, Menu.GROUP, "시스템관리", null, "👤", 50, false, true, false),
            new Menu("apiguide", "g_system", Menu.PAGE, "API 가이드(예제·소스)", "/admin/api-guide", null, 10, false, true, false),
            new Menu("swagger", "g_system", Menu.LINK, "API 문서(Swagger)", "/swagger-ui.html", null, 20, false, true, true),
            new Menu("manual", null, Menu.LINK, "사용자 매뉴얼", "/manual/index.html", "📖", 60, true, false, true)));

    /** API 문서를 끈 운영 환경(prod)에서 숨기는 메뉴 */
    private static final Set<String> API_DOC_MENUS = new HashSet<>(Arrays.asList("apiguide", "swagger"));

    private final MenuMapper mapper;
    private final JdbcTemplate jdbc;
    private volatile List<Menu> cache = Collections.emptyList();

    @Value("${app.api-docs.enabled:true}")
    private boolean apiDocsEnabled = true;

    public MenuService(MenuMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ---------------------------------------------------------------- 시작

    @PostConstruct
    public void init() {
        ensureTable();
        Set<String> have = new HashSet<>();
        for (Menu m : mapper.selectMenuList()) have.add(m.getMenuId());
        int added = 0;
        for (Menu d : DEFAULTS) {
            if (have.contains(d.getMenuId())) continue;
            mapper.insertMenu(copy(d));
            added++;
        }
        if (added > 0) log.info("MENU 기본 메뉴 {}건 추가", added);
        reload();
    }

    /** tb_menu 가 없는 DB (v10 이전) 에서 바로 만들기 - MySQL / MariaDB / H2 공통 DDL */
    private void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_menu ("
                + " menu_id VARCHAR(30) NOT NULL,"
                + " parent_id VARCHAR(30),"
                + " menu_type VARCHAR(10) NOT NULL,"
                + " menu_nm VARCHAR(50) NOT NULL,"
                + " default_nm VARCHAR(50) NOT NULL,"
                + " url VARCHAR(200),"
                + " icon VARCHAR(10),"
                + " sort_ord INT DEFAULT 0 NOT NULL,"
                + " user_yn CHAR(1) DEFAULT 'Y' NOT NULL,"
                + " admin_only CHAR(1) DEFAULT 'N' NOT NULL,"
                + " new_window CHAR(1) DEFAULT 'N' NOT NULL,"
                + " upd_user VARCHAR(30),"
                + " upd_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                + " PRIMARY KEY (menu_id))");
    }

    public synchronized void reload() {
        cache = Collections.unmodifiableList(new ArrayList<>(mapper.selectMenuList()));
    }

    private static Menu copy(Menu d) {
        Menu m = new Menu(d.getMenuId(), d.getParentId(), d.getMenuType(), d.getDefaultNm(), d.getUrl(), d.getIcon(),
                d.getSortOrd(), d.isUserAllowed(), d.isAdminOnlyMenu(), d.isNewWindowLink());
        m.setUpdUser("system");
        return m;
    }

    // ---------------------------------------------------------------- 조회

    public List<Menu> all() {
        return cache;
    }

    Menu find(String id) {
        for (Menu m : cache) if (m.getMenuId().equals(id)) return m;
        return null;
    }

    private boolean hidden(Menu m) {
        return !apiDocsEnabled && API_DOC_MENUS.contains(m.getMenuId());
    }

    /** 이 사용자가 볼 수 있는지 (그룹 제외) */
    public boolean isAllowed(boolean admin, String menuId) {
        Menu m = find(menuId);
        if (m == null) return true;                 // 메뉴에 없는 화면은 제한하지 않음
        if (hidden(m)) return false;
        if (admin || MAIN.equals(menuId)) return true;
        if (m.isAdminOnlyMenu() || !m.isUserAllowed()) return false;
        Menu p = m.getParentId() == null ? null : find(m.getParentId());
        return p == null || !p.isAdminOnlyMenu();
    }

    /** 화면 주소 → 메뉴 (PAGE 만, 정확히 같은 주소) */
    public Menu findPage(String path) {
        for (Menu m : cache) {
            if (Menu.PAGE.equals(m.getMenuType()) && m.getUrl() != null && m.getUrl().equals(path)) return m;
        }
        return null;
    }

    /** 좌측 메뉴 트리 (보이는 것만) */
    public List<Node> visibleTree(boolean admin) {
        List<Node> top = new ArrayList<>();
        Map<String, Node> groups = new HashMap<>();
        for (Menu m : cache) {
            if (m.getParentId() != null) continue;
            if (m.isGroup()) {
                if (!admin && m.isAdminOnlyMenu()) continue;
                Node g = new Node(m);
                groups.put(m.getMenuId(), g);
                top.add(g);
            } else if (isAllowed(admin, m.getMenuId())) {
                top.add(new Node(m));
            }
        }
        for (Menu m : cache) {
            if (m.getParentId() == null) continue;
            Node g = groups.get(m.getParentId());
            if (g != null && isAllowed(admin, m.getMenuId())) g.children.add(new Node(m));
        }
        top.removeIf(n -> n.group && n.children.isEmpty());
        return top;
    }

    /** 화면 위쪽 경로 표시 : 메인 홈 > 그룹 > 메뉴 */
    public String crumb(String menuId) {
        Menu main = find(MAIN);
        String home = main != null ? main.getMenuNm() : "메인 홈";
        Menu m = menuId == null ? null : find(menuId);
        if (m == null || MAIN.equals(menuId)) return home;
        Menu p = m.getParentId() == null ? null : find(m.getParentId());
        return home + " > " + (p != null ? p.getMenuNm() + " > " : "") + m.getMenuNm();
    }

    /** 화면 이름 (탭 제목 등) */
    public String name(String menuId) {
        Menu m = find(menuId);
        return m != null ? m.getMenuNm() : "";
    }

    // ---------------------------------------------------------------- 변경 (ADMIN)

    /** 일반 사용자 메뉴 권한 저장 : allowed 에 있는 메뉴만 허용 (관리자 전용·메인 홈·그룹은 바꾸지 않음) */
    @Transactional
    public List<Menu> saveUserAccess(Collection<String> allowed, String user) {
        Set<String> ok = new HashSet<>(allowed == null ? Collections.<String>emptySet() : allowed);
        for (String id : ok) {
            if (find(id) == null) throw TopologyException.badRequest("알 수 없는 메뉴입니다 : " + id);
        }
        int changed = 0;
        for (Menu m : cache) {
            if (m.isGroup() || m.isAdminOnlyMenu() || MAIN.equals(m.getMenuId())) continue;
            String yn = ok.contains(m.getMenuId()) ? "Y" : "N";
            if (!yn.equals(m.getUserYn())) {
                mapper.updateUserYn(m.getMenuId(), yn, user);
                changed++;
            }
        }
        reload();
        log.info("MENU 일반 사용자 권한 저장 by {} : 허용 {} (변경 {}건)", user, ok, changed);
        return cache;
    }

    /** 배치 1건 : 메뉴 ID, 부모(그룹) ID, 순서, 이름 */
    public static class LayoutItem {
        public String id;
        public String parentId;
        public int sortOrd;
        public String name;
    }

    /** 메뉴 이름·순서·그룹 저장 (끌어서 놓기 결과) */
    @Transactional
    public List<Menu> saveLayout(List<LayoutItem> items, String user) {
        if (items == null || items.isEmpty()) throw TopologyException.badRequest("저장할 메뉴가 없습니다.");
        Map<String, LayoutItem> byId = new LinkedHashMap<>();
        for (LayoutItem it : items) {
            Menu m = it == null ? null : find(it.id);
            if (m == null) throw TopologyException.badRequest("알 수 없는 메뉴입니다 : " + (it == null ? null : it.id));
            String nm = it.name == null ? "" : it.name.trim();
            if (nm.isEmpty() || nm.length() > 50) throw TopologyException.badRequest("메뉴 이름은 1~50자로 입력하세요 : " + m.getMenuNm());
            String pid = it.parentId == null || it.parentId.trim().isEmpty() ? null : it.parentId.trim();
            if (m.isGroup() && pid != null) throw TopologyException.badRequest("그룹(" + nm + ")은 다른 그룹 안에 넣을 수 없습니다.");
            if (pid != null) {
                Menu p = find(pid);
                if (p == null || !p.isGroup()) throw TopologyException.badRequest("상위 메뉴는 그룹이어야 합니다 : " + pid);
            }
            it.parentId = pid;
            it.name = nm;
            byId.put(m.getMenuId(), it);
        }
        if (byId.size() != cache.size()) throw TopologyException.badRequest("모든 메뉴를 함께 저장해야 합니다 (" + byId.size() + " / " + cache.size() + ")");
        for (LayoutItem it : byId.values()) {
            mapper.updateLayout(it.id, it.parentId, it.sortOrd, it.name, user);
        }
        reload();
        log.info("MENU 이름·순서 저장 by {}", user);
        return cache;
    }

    /** 처음 이름·순서·그룹·권한으로 되돌리기 */
    @Transactional
    public List<Menu> resetDefaults(String user) {
        for (Menu d : DEFAULTS) {
            Menu m = copy(d);
            m.setUpdUser(user);
            if (mapper.resetMenu(m) == 0) mapper.insertMenu(m);
        }
        reload();
        log.info("MENU 기본값으로 되돌림 by {}", user);
        return cache;
    }

    // ---------------------------------------------------------------- 화면용 트리 노드

    /** 좌측 메뉴 노드 (Thymeleaf) */
    public static class Node {
        public final String id;
        public final String name;
        public final String icon;
        public final String url;
        public final boolean group;
        public final boolean newWindow;
        public final List<Node> children = new ArrayList<>();

        Node(Menu m) {
            this.id = m.getMenuId();
            this.name = m.getMenuNm();
            this.icon = m.getIcon();
            this.url = m.getUrl();
            this.group = m.isGroup();
            this.newWindow = m.isNewWindowLink();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getIcon() { return icon; }
        public String getUrl() { return url; }
        public boolean isGroup() { return group; }
        public boolean isNewWindow() { return newWindow; }
        public List<Node> getChildren() { return children; }
    }
}
