package com.scb.monitoring.serverlog.controller;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.scb.monitoring.serverlog.dto.LogSourceForm;
import com.scb.monitoring.serverlog.dto.ServerLogView;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.svcctl.service.ServiceCtlService;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 로그 화면 (/logs) 과 API. 조회는 로그인 사용자, 변경(주기·지금 요청·로그 등록)은 ADMIN (SecurityConfig).
 * <pre>
 * GET    /api/logs/{serverId}                               요청 주기 + 로그 목록 + 에이전트 상태
 * PUT    /api/logs/{serverId}/policy        {"intervalMin":5}
 * POST   /api/logs/{serverId}/request                       지금 요청
 * POST   /api/logs/{serverId}/sources       {name,path,tailLines,use}
 * PUT    /api/logs/{serverId}/sources/{logId}
 * DELETE /api/logs/{serverId}/sources/{logId}
 * GET    /api/logs/{serverId}/sources/{logId}/snapshots     수집 시각 목록 (최근 20회)
 * GET    /api/logs/{serverId}/snapshots/{snapId}            수집 내용
 * </pre>
 */
@Controller
public class ServerLogController {

    private final ServerLogService service;
    private final ServiceCtlService serviceCtl;

    public ServerLogController(ServerLogService service, ServiceCtlService serviceCtl) {
        this.service = service;
        this.serviceCtl = serviceCtl;
    }

    @GetMapping("/logs")
    public String page(Model model) {
        model.addAttribute("menu", "logs");
        model.addAttribute("servers", serviceCtl.getServerOptions());
        return "server_logs";
    }

    @GetMapping("/api/logs/{serverId}")
    @ResponseBody
    public ServerLogView get(@PathVariable("serverId") Long serverId) {
        return service.getServerLogs(serverId);
    }

    @PutMapping("/api/logs/{serverId}/policy")
    @ResponseBody
    public ServerLogView policy(@PathVariable("serverId") Long serverId, @RequestBody Map<String, Object> req, Authentication auth) {
        Object v = req.get("intervalMin");
        int min;
        try {
            min = Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw TopologyException.badRequest("요청 주기 값이 올바르지 않습니다.");
        }
        return service.changeInterval(serverId, min, auth.getName());
    }

    @PostMapping("/api/logs/{serverId}/request")
    @ResponseBody
    public ServerLogView requestNow(@PathVariable("serverId") Long serverId, Authentication auth) {
        return service.requestNow(serverId, auth.getName());
    }

    @PostMapping("/api/logs/{serverId}/sources")
    @ResponseBody
    public ServerLogView.Source add(@PathVariable("serverId") Long serverId, @Valid @RequestBody LogSourceForm form) {
        return service.addSource(serverId, form);
    }

    @PutMapping("/api/logs/{serverId}/sources/{logId}")
    @ResponseBody
    public ServerLogView.Source update(@PathVariable("serverId") Long serverId, @PathVariable("logId") Long logId,
                                       @Valid @RequestBody LogSourceForm form) {
        return service.updateSource(serverId, logId, form);
    }

    @DeleteMapping("/api/logs/{serverId}/sources/{logId}")
    @ResponseBody
    public Map<String, String> delete(@PathVariable("serverId") Long serverId, @PathVariable("logId") Long logId) {
        service.deleteSource(serverId, logId);
        return java.util.Collections.singletonMap("message", "삭제되었습니다.");
    }

    @GetMapping("/api/logs/{serverId}/sources/{logId}/snapshots")
    @ResponseBody
    public List<ServerLogView.Snapshot> snapshots(@PathVariable("serverId") Long serverId, @PathVariable("logId") Long logId) {
        return service.getSnapshots(serverId, logId);
    }

    @GetMapping("/api/logs/{serverId}/snapshots/{snapId}")
    @ResponseBody
    public ServerLogView.Snapshot snapshot(@PathVariable("serverId") Long serverId, @PathVariable("snapId") Long snapId) {
        return service.getSnapshot(serverId, snapId);
    }
}
