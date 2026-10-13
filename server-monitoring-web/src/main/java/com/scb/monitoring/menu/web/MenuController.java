package com.scb.monitoring.menu.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.scb.monitoring.menu.domain.Menu;
import com.scb.monitoring.menu.service.MenuService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 권한관리 화면 (ADMIN) : [메뉴 권한] /admin/menu-auth, [메뉴 이름·순서] /admin/menus (끌어서 놓기).
 * API : GET /api/admin/menus, PUT /api/admin/menus/access, PUT /api/admin/menus/layout, POST /api/admin/menus/reset
 */
@Tag(name = "6. 사용자·권한", description = "사용자 관리, 메뉴 권한, 메뉴 이름·순서")
@Controller
public class MenuController {

    private final MenuService service;

    public MenuController(MenuService service) {
        this.service = service;
    }

    @GetMapping("/admin/menu-auth")
    public String menuAuth(Model model) {
        model.addAttribute("menu", "menuauth");
        return "menu_auth";
    }

    @GetMapping("/admin/menus")
    public String menuEdit(Model model) {
        model.addAttribute("menu", "menuedit");
        return "menu_edit";
    }

    @Operation(summary = "전체 메뉴 (이름·순서·그룹·일반 사용자 허용 여부) - ADMIN")
    @GetMapping("/api/admin/menus")
    @ResponseBody
    public List<Map<String, Object>> list() {
        return view(service.all());
    }

    @Operation(summary = "일반 사용자 메뉴 권한 저장 - ADMIN : allowed = 허용할 메뉴 ID 목록 (관리자는 항상 전체)")
    @PutMapping("/api/admin/menus/access")
    @ResponseBody
    public List<Map<String, Object>> saveAccess(@RequestBody Map<String, List<String>> body, Authentication auth) {
        return view(service.saveUserAccess(body.get("allowed"), auth.getName()));
    }

    @Operation(summary = "메뉴 이름·순서·그룹 저장 - ADMIN : 전체 메뉴 [{id, parentId, sortOrd, name}]")
    @PutMapping("/api/admin/menus/layout")
    @ResponseBody
    public List<Map<String, Object>> saveLayout(@RequestBody List<MenuService.LayoutItem> items, Authentication auth) {
        return view(service.saveLayout(items, auth.getName()));
    }

    @Operation(summary = "메뉴를 처음 이름·순서·권한으로 되돌리기 - ADMIN")
    @PostMapping("/api/admin/menus/reset")
    @ResponseBody
    public List<Map<String, Object>> reset(Authentication auth) {
        return view(service.resetDefaults(auth.getName()));
    }

    private List<Map<String, Object>> view(List<Menu> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Menu m : list) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", m.getMenuId());
            v.put("parentId", m.getParentId());
            v.put("type", m.getMenuType());
            v.put("name", m.getMenuNm());
            v.put("defaultName", m.getDefaultNm());
            v.put("url", m.getUrl());
            v.put("icon", m.getIcon());
            v.put("sortOrd", m.getSortOrd());
            v.put("userAllowed", m.isUserAllowed());
            v.put("adminOnly", m.isAdminOnlyMenu());
            v.put("fixed", MenuService.MAIN.equals(m.getMenuId()));
            v.put("newWindow", m.isNewWindowLink());
            v.put("updUser", m.getUpdUser());
            v.put("updDt", m.getUpdDt() == null ? null : m.getUpdDt().toString().replaceFirst("\\.\\d+$", ""));
            out.add(v);
        }
        return out;
    }

    /** 메뉴 권한 확인 인터셉터 등록 (화면 주소만, API·정적 파일 제외) */
    @Configuration
    public static class MenuWebConfig implements WebMvcConfigurer {
        private final MenuService service;

        public MenuWebConfig(MenuService service) {
            this.service = service;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new MenuAccessInterceptor(service))
                    .addPathPatterns("/**")
                    .excludePathPatterns("/api/**", "/agent-api/**", "/css/**", "/js/**", "/images/**", "/vendor/**",
                            "/manual/**", "/samples/**", "/login", "/signup", "/password", "/error", "/v3/**", "/swagger-ui/**");
        }
    }
}
