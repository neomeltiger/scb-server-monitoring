package com.scb.monitoring.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.scb.monitoring.auth.controller.UserAdminController;
import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.service.UserAdminService;
import com.scb.monitoring.svcctl.controller.ServiceCtlPageController;
import com.scb.monitoring.topology.controller.TopologyPageController;

/** 화면 공통 모델 : 좌측 메뉴의 가입 승인 대기 건수 (ADMIN 일 때만 조회), API 문서 메뉴 표시 여부 (운영 prod 는 숨김) */
@ControllerAdvice(assignableTypes = {PageController.class, TopologyPageController.class,
        ServiceCtlPageController.class, UserAdminController.class,
        com.scb.monitoring.serverlog.controller.ServerLogController.class,
        com.scb.monitoring.agent.controller.AgentPageController.class, ApiGuidePageController.class,
        com.scb.monitoring.menu.web.MenuController.class, com.scb.monitoring.settings.SettingsController.class})
public class LayoutModelAdvice {

    private final UserAdminService userAdminService;

    public LayoutModelAdvice(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @Value("${app.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    @ModelAttribute("apiDocsEnabled")
    public boolean apiDocsEnabled() {
        return apiDocsEnabled;
    }

    @ModelAttribute("pendingUserCount")
    public Integer pendingUserCount(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AppUser) || !((AppUser) auth.getPrincipal()).isAdmin()) return null;
        return userAdminService.countPending();
    }
}
