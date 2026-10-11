package com.scb.monitoring.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 화면 라우팅. 각 화면은 templates/ 의 Thymeleaf 템플릿이며,
 * menu 값으로 좌측 메뉴(fragments/left.html)의 현재 메뉴를 표시합니다.
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String main(Model model) {
        model.addAttribute("menu", "main");
        return "index";
    }

    // /network (네트워크 구성도) 는 topology.controller.TopologyPageController

    @GetMapping("/servers")
    public String servers(Model model) {
        model.addAttribute("menu", "servers");
        return "server_spec";
    }

    // /processes (프로세스 관리) 는 svcctl.controller.ServiceCtlPageController


    /** 웹 콘솔 (별도 창) : /console?id=서버ID&port=22 또는 /console?host=주소&port=22 */
    @GetMapping("/console")
    public String console() {
        return "web_console";
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/";
        }
        return "login";
    }
}
