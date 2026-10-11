package com.scb.monitoring.topology.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.scb.monitoring.topology.service.TopologyService;

/**
 * 네트워크 구성도 화면 (MVC 의 View 렌더링).
 * 설치장소 선택 목록(스위치)은 tb_net_node 에서 읽어 Model 로 전달하고,
 * 서버·프로세스 데이터는 화면에서 /api/topology 로 조회/변경합니다.
 */
@Controller
public class TopologyPageController {

    private final TopologyService service;

    public TopologyPageController(TopologyService service) {
        this.service = service;
    }

    @GetMapping("/network")
    public String network(Model model) {
        model.addAttribute("menu", "network");
        model.addAttribute("switches", service.getSwitches());
        return "network_topology";
    }
}
