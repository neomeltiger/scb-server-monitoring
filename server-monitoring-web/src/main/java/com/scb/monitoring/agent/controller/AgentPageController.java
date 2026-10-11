package com.scb.monitoring.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 에이전트 관리 화면 (/agents) : 서버별 krcasmrx11_client 데몬 상태,
 * 자료 수집 시작 / 중지 / 데몬 종료, 수집 주기 설정 (ADMIN). 데이터는 /api/agents (AgentAdminController).
 */
@Controller
public class AgentPageController {

    @GetMapping("/agents")
    public String agents(Model model) {
        model.addAttribute("menu", "agents");
        return "agent_manage";
    }
}
