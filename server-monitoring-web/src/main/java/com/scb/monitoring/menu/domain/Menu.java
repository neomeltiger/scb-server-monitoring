package com.scb.monitoring.menu.domain;

import java.sql.Timestamp;

/**
 * 좌측 메뉴 (tb_menu).
 * <ul>
 *   <li>menuType : GROUP (하위 메뉴 묶음) / PAGE (이 웹의 화면) / LINK (새 창 - 매뉴얼, Swagger)</li>
 *   <li>userYn : 일반 사용자 접근 허용 (관리자는 항상 전체 접근)</li>
 *   <li>adminOnly : 관리자 전용 고정 (권한관리·시스템관리 - 일반 사용자에게 줄 수 없음)</li>
 *   <li>menuNm : 화면에 보이는 이름 (변경 가능), defaultNm : 처음 이름</li>
 * </ul>
 */
public class Menu {

    public static final String GROUP = "GROUP";
    public static final String PAGE = "PAGE";
    public static final String LINK = "LINK";

    private String menuId;
    private String parentId;
    private String menuType;
    private String menuNm;
    private String defaultNm;
    private String url;
    private String icon;
    private int sortOrd;
    private String userYn = "Y";
    private String adminOnly = "N";
    private String newWindow = "N";
    private String updUser;
    private Timestamp updDt;

    public Menu() {
    }

    public Menu(String menuId, String parentId, String menuType, String name, String url, String icon, int sortOrd,
                boolean user, boolean adminOnly, boolean newWindow) {
        this.menuId = menuId;
        this.parentId = parentId;
        this.menuType = menuType;
        this.menuNm = name;
        this.defaultNm = name;
        this.url = url;
        this.icon = icon;
        this.sortOrd = sortOrd;
        this.userYn = user ? "Y" : "N";
        this.adminOnly = adminOnly ? "Y" : "N";
        this.newWindow = newWindow ? "Y" : "N";
    }

    public boolean isGroup() { return GROUP.equals(menuType); }
    public boolean isUserAllowed() { return "Y".equals(userYn); }
    public boolean isAdminOnlyMenu() { return "Y".equals(adminOnly); }
    public boolean isNewWindowLink() { return "Y".equals(newWindow); }

    public String getMenuId() { return menuId; }
    public void setMenuId(String menuId) { this.menuId = menuId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getMenuType() { return menuType; }
    public void setMenuType(String menuType) { this.menuType = menuType; }
    public String getMenuNm() { return menuNm; }
    public void setMenuNm(String menuNm) { this.menuNm = menuNm; }
    public String getDefaultNm() { return defaultNm; }
    public void setDefaultNm(String defaultNm) { this.defaultNm = defaultNm; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getIcon() { return icon; }

    /**
     * 좌측 메뉴에 보일 아이콘 : 지정한 아이콘, 없으면 기본 아이콘 (그룹 📁 / 화면 📄 / 새 창 링크 🔗).
     * 하위 메뉴(아이콘 없음)를 [메뉴 이름·순서] 에서 맨 위 단계로 옮겼을 때 빈칸이 되지 않도록.
     */
    public String getDisplayIcon() {
        if (icon != null && !icon.trim().isEmpty()) return icon;
        return defaultIcon(menuType);
    }

    public static String defaultIcon(String menuType) {
        if (GROUP.equals(menuType)) return "📁";
        if (LINK.equals(menuType)) return "🔗";
        return "📄";
    }
    public void setIcon(String icon) { this.icon = icon; }
    public int getSortOrd() { return sortOrd; }
    public void setSortOrd(int sortOrd) { this.sortOrd = sortOrd; }
    public String getUserYn() { return userYn; }
    public void setUserYn(String userYn) { this.userYn = userYn; }
    public String getAdminOnly() { return adminOnly; }
    public void setAdminOnly(String adminOnly) { this.adminOnly = adminOnly; }
    public String getNewWindow() { return newWindow; }
    public void setNewWindow(String newWindow) { this.newWindow = newWindow; }
    public String getUpdUser() { return updUser; }
    public void setUpdUser(String updUser) { this.updUser = updUser; }
    public Timestamp getUpdDt() { return updDt; }
    public void setUpdDt(Timestamp updDt) { this.updDt = updDt; }
}
