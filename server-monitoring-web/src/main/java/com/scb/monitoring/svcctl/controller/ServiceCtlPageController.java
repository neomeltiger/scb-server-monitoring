package com.scb.monitoring.svcctl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.scb.monitoring.svcctl.service.ServiceCtlService;

/**
 * 프로세스 관리 화면 (/processes). 서버 선택 목록은 Model 로 전달하고,
 * 서비스 목록/상태/제어는 화면에서 /api/services 로 처리합니다.
 */
@Controller
public class ServiceCtlPageController {

    private final ServiceCtlService service;

    public ServiceCtlPageController(ServiceCtlService service) {
        this.service = service;
    }

    @GetMapping("/processes")
    public String processes(Model model) {
        model.addAttribute("menu", "processes");
        model.addAttribute("servers", service.getServerOptions());
        return "process_monitoring";
    }
}
