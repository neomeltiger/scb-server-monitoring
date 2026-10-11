package com.scb.monitoring.agent.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.dto.AgentStatusView;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.service.AgentService;

/**
 * 에이전트 관리 (화면용, 로그인 필요). 토큰 발급(POST)은 ADMIN 만 가능 (SecurityConfig).
 * <pre>
 * GET  /api/agents                    전체 서버 에이전트 등록 현황 (메인 홈 미등록 서버 패널)
 * GET  /api/agents/{serverId}         에이전트 상태 (등록 확인)
 * POST /api/agents/{serverId}/token   토큰 (재)발급 - 원문은 이 응답에서 한 번만 보여줌
 * POST /api/agents/{serverId}/daemon  {"action":"start|stop|shutdown"}  데몬 자료 수집 시작 / 중지 / 데몬 종료
 * PUT  /api/agents/{serverId}/collect-interval {"sec":60}              수집 주기 (10/30/60/120/300/600초)
 * </pre>
 */
@Tag(name = "5. 에이전트 관리", description = "에이전트 상태·데몬 제어·수집 주기·토큰 발급")
@RestController
@RequestMapping("/api/agents")
public class AgentAdminController {

    private final AgentService service;
    private final AgentProperties props;

    public AgentAdminController(AgentService service, AgentProperties props) {
        this.service = service;
        this.props = props;
    }

    @Operation(summary = "에이전트 목록·상태")
    @GetMapping
    public List<AgentStatusView> list() {
        return service.getAgentStatusList();
    }

    @Operation(summary = "에이전트 상세")
    @GetMapping("/{serverId}")
    public AgentView agent(@PathVariable("serverId") Long serverId) {
        return service.getAgent(serverId);
    }

    @Operation(summary = "데몬 제어 (시작/중지/종료) - ADMIN")
    @PostMapping("/{serverId}/daemon")
    public AgentView daemon(@PathVariable("serverId") Long serverId, @RequestBody Map<String, Object> body, Authentication auth) {
        Object a = body.get("action");
        return service.controlDaemon(serverId, a == null ? "" : String.valueOf(a), auth.getName());
    }

    @Operation(summary = "수집 주기 변경 - ADMIN")
    @PutMapping("/{serverId}/collect-interval")
    public AgentView collectInterval(@PathVariable("serverId") Long serverId, @RequestBody Map<String, Object> body, Authentication auth) {
        int sec;
        try {
            sec = Integer.parseInt(String.valueOf(body.get("sec")));
        } catch (NumberFormatException e) {
            throw com.scb.monitoring.topology.service.TopologyException.badRequest("수집 주기 값이 올바르지 않습니다.");
        }
        return service.changeCollectInterval(serverId, sec, auth.getName());
    }

    @Operation(summary = "토큰 발급(재발급) - ADMIN")
    @PostMapping("/{serverId}/token")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable("serverId") Long serverId,
                                                     Authentication auth, HttpServletRequest req) {
        String token = service.issueToken(serverId, auth.getName());
        AgentWebUrl url = AgentWebUrl.resolve(props.getPublicUrl(),
                ServletUriComponentsBuilder.fromContextPath(req).build().toUriString());
        String webUrl = url.getUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverId", serverId);
        body.put("token", token);
        body.put("webUrl", webUrl);
        body.put("urlWarning", url.getWarning());
        body.put("urlCandidates", url.getCandidates());
        // 서버의 /shared/opt/agent/krcasmrx11_client/etc/client.env 에 넣을 값
        body.put("clientEnv", "SCB_WEB_URL=" + webUrl + "\nSCB_AGENT_TOKEN=" + token + "\n");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
