package com.scb.monitoring.menu.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import com.scb.monitoring.menu.domain.Menu;
import com.scb.monitoring.menu.service.MenuService;

/**
 * 메뉴 권한 확인 : 일반 사용자가 허용되지 않은 메뉴 화면(GET)을 열면 메인 홈으로 보내고 안내 (?denied=메뉴이름).
 * 관리자는 모든 화면. 관리자 전용 주소(/admin/**)는 Spring Security 가 먼저 막음 (SecurityConfig).
 */
public class MenuAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MenuAccessInterceptor.class);

    private final MenuService menus;

    public MenuAccessInterceptor(MenuService menus) {
        this.menus = menus;
    }

    public static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return true;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.isEmpty()) path = "/";
        Menu m = menus.findPage(path);
        if (m == null) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (menus.isAllowed(isAdmin(auth), m.getMenuId())) return true;
        log.info("MENU 접근 거부 user={} menu={} ({})", auth == null ? "-" : auth.getName(), m.getMenuId(), path);
        res.sendRedirect(req.getContextPath() + "/?denied=" + URLEncoder.encode(m.getMenuNm(), StandardCharsets.UTF_8.name()));
        return false;
    }
}
