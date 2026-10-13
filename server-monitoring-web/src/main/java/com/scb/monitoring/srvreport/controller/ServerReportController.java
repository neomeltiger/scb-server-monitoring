package com.scb.monitoring.srvreport.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.scb.monitoring.srvreport.dto.ServerReportView;
import com.scb.monitoring.srvreport.service.ServerReportService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 점검 리포트 화면 (/admin/server-report) 과 API - 모두 ADMIN (SecurityConfig : /admin/**, /api/admin/**).
 * 리포트는 관리자가 [리포트 요청] 을 눌렀을 때만 서버(svr_collect_client 1.12~)에서 만들어 보냅니다.
 * <pre>
 * GET    /api/admin/server-report/{serverId}                   에이전트 상태 + 리포트 목록 (최근 20건)
 * POST   /api/admin/server-report/{serverId}   {"sections":["sec","res","svc","chg"]}   리포트 요청
 * GET    /api/admin/server-report/{serverId}/{reportId}        리포트 내용
 * DELETE /api/admin/server-report/{serverId}/{reportId}        리포트 삭제
 * </pre>
 */
@Tag(name = "4-2. 서버 점검 리포트", description = "보안·자원·서비스·패키지 점검 리포트 (관리자, 요청할 때만)")
@Controller
public class ServerReportController {

    private final ServerReportService service;
    private final ServiceCtlService serviceCtl;

    public ServerReportController(ServerReportService service, ServiceCtlService serviceCtl) {
        this.service = service;
        this.serviceCtl = serviceCtl;
    }

    @Hidden
    @GetMapping("/admin/server-report")
    public String page(Model model) {
        model.addAttribute("menu", "srvreport");
        model.addAttribute("servers", serviceCtl.getServerOptions());
        return "server_report";
    }

    @Operation(summary = "에이전트 상태 + 리포트 목록 - ADMIN")
    @GetMapping("/api/admin/server-report/{serverId}")
    @ResponseBody
    public ServerReportView get(@PathVariable("serverId") Long serverId) {
        return service.getView(serverId);
    }

    @Operation(summary = "리포트 요청 - ADMIN")
    @PostMapping("/api/admin/server-report/{serverId}")
    @ResponseBody
    public ServerReportView request(@PathVariable("serverId") Long serverId, @RequestBody(required = false) Map<String, Object> req,
                                    Authentication auth) {
        List<String> sections = new ArrayList<>();
        Object v = req == null ? null : req.get("sections");
        if (v instanceof List) {
            for (Object o : (List<?>) v) sections.add(String.valueOf(o));
        } else if (v != null) {
            for (String s : String.valueOf(v).split(",")) sections.add(s);
        }
        if (v != null && sections.isEmpty()) throw TopologyException.badRequest("분야를 하나 이상 고르세요.");
        return service.request(serverId, sections, auth.getName());
    }

    @Operation(summary = "리포트 내용 - ADMIN")
    @GetMapping("/api/admin/server-report/{serverId}/{reportId}")
    @ResponseBody
    public ServerReportView.Detail report(@PathVariable("serverId") Long serverId, @PathVariable("reportId") Long reportId) {
        return service.getReport(serverId, reportId);
    }

    @Operation(summary = "리포트 삭제 - ADMIN")
    @DeleteMapping("/api/admin/server-report/{serverId}/{reportId}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable("serverId") Long serverId, @PathVariable("reportId") Long reportId,
                                      Authentication auth) {
        service.delete(serverId, reportId, auth.getName());
        return java.util.Collections.singletonMap("deleted", reportId);
    }
}
