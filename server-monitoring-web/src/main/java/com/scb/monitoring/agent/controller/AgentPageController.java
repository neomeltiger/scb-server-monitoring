package com.scb.monitoring.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 에이전트 관리 화면 (/agents) : 서버별 svr_collect_client 데몬 상태,
 * 자료 수집 시작 / 중지 / 데몬 종료, 수집 주기 설정 (ADMIN). 데이터는 /api/agents (AgentAdminController).
 */
@Controller
public class AgentPageController {

    /** 하트비트 모니터링 (/heartbeat) : 서버별 생존 신호, 끊김·복구 이력 - 데이터는 /api/agents/heartbeat */
    @GetMapping("/heartbeat")
    public String heartbeat(Model model) {
        model.addAttribute("menu", "heartbeat");
        return "heartbeat";
    }

    /** 데몬 중단 중 프로세스 현황 (/fallback) : 데몬이 죽어도 감시자가 대신 수집한 서버 프로세스 정보 - 데이터는 /api/agents/fallback */
    @GetMapping("/fallback")
    public String fallback(Model model) {
        model.addAttribute("menu", "fallback");
        return "fallback";
    }

    @GetMapping("/agents")
    public String agents(Model model) {
        model.addAttribute("menu", "agents");
        return "agent_manage";
    }
}
