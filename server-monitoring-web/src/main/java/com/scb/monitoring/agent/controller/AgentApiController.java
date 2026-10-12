package com.scb.monitoring.agent.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.service.AgentService;
import com.scb.monitoring.serverlog.service.ServerLogService;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 에이전트(krcasmrx11_client) 전용 API. 로그인 세션 대신 토큰 헤더로 인증합니다.
 * <pre>
 * 헤더  : X-SCB-Agent-Token: (토큰 64자리)
 * GET  /agent-api/v1/ping                     연결 확인
 * GET  /agent-api/v1/heartbeat?pid=N&daemon=RUNNING|PAUSED|STOPPED|DEAD&boot=Y|N&btime=부팅시각&hbver=감시자버전&fb=대신수집결과
 *                                             하트비트 (bin/scb_heartbeat.sh, daemon 은 1.5~ 감시자) → hb|interval=10|timeout=32|restart=N
 * GET  /agent-api/v1/config                   보고 주기, 감시 프로세스, 동기화 대상 IP
 * POST /agent-api/v1/report                   수집 결과 (text/plain)
 * GET  /agent-api/v1/commands?state=RUNNING&pid=N&ver=V
 *                                             데몬 확인(heartbeat) + 웹 설정(ctl|...) + 제어 명령(cmd|...) + 로그 요청(log|...)
 * POST /agent-api/v1/daemon?state=STOPPED     데몬 종료 보고
 * POST /agent-api/v1/fallback                 데몬 중단 중 감시자가 대신 수집한 결과 ([metric] [proc] [service], 1.7~)
 * POST /agent-api/v1/commands/{id}/result?exit=N  실행 결과 (본문 = 출력)
 * POST /agent-api/v1/logs/{logId}?status=OK      요청받은 서버 로그 (본문 = 마지막 N줄, status: OK|NOT_FOUND|DENIED|ERROR)
 * </pre>
 * 응답은 모두 text/plain (Bourne shell 에서 처리하기 쉽도록).
 */
@Tag(name = "9. 에이전트 연동", description = "서버 에이전트(krcasmrx11_client) 전용 - X-SCB-Agent-Token 헤더 인증, 응답 text/plain")
@RestController
@RequestMapping(value = "/agent-api/v1", produces = "text/plain;charset=UTF-8")
public class AgentApiController {

    private static final Logger log = LoggerFactory.getLogger(AgentApiController.class);
    public static final String TOKEN_HEADER = "X-SCB-Agent-Token";

    private final AgentService service;
    private final AgentProperties props;
    private final ServerLogService serverLogService;
    private final com.scb.monitoring.agent.service.HeartbeatService heartbeat;
    private final com.scb.monitoring.agent.service.FallbackService fallback;

    public AgentApiController(AgentService service, AgentProperties props, ServerLogService serverLogService,
                              com.scb.monitoring.agent.service.HeartbeatService heartbeat,
                              com.scb.monitoring.agent.service.FallbackService fallback) {
        this.fallback = fallback;
        this.heartbeat = heartbeat;
        this.service = service;
        this.props = props;
        this.serverLogService = serverLogService;
    }

    @Operation(summary = "연결 확인")
    @GetMapping("/ping")
    public ResponseEntity<String> ping(@RequestHeader(value = TOKEN_HEADER, required = false) String token, HttpServletRequest req) {
        Long serverId = auth(token, req);
        return text("ok server_id=" + serverId + "\n");
    }

    @Operation(summary = "하트비트 (생존 신호 + 데몬 상태) - 응답 hb|interval=초|timeout=초|restart=Y/N")
    @GetMapping("/heartbeat")
    public ResponseEntity<String> heartbeat(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                            @RequestParam(value = "pid", required = false) Integer pid,
                                            @RequestParam(value = "daemon", required = false) String daemon,
                                            @RequestParam(value = "boot", required = false) String boot,
                                            @RequestParam(value = "btime", required = false) String btime,
                                            @RequestParam(value = "hbver", required = false) String hbver,
                                            @RequestParam(value = "fb", required = false) String fb,
                                            HttpServletRequest req) {
        Long serverId = auth(token, req);
        if (daemon != null) fallback.noteWatchdog(serverId, hbver, fb);
        return text(heartbeat.beat(serverId, pid, daemon, boot, btime));
    }

    @Operation(summary = "에이전트 설정 (보고 주기, 감시 프로세스)")
    @GetMapping("/config")
    public ResponseEntity<String> config(@RequestHeader(value = TOKEN_HEADER, required = false) String token, HttpServletRequest req) {
        return text(service.buildConfig(auth(token, req)));
    }

    @Operation(summary = "수집 결과 보고 (text/plain)")
    @PostMapping(value = "/report", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> report(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                         @RequestBody(required = false) byte[] body, HttpServletRequest req) {
        Long serverId = auth(token, req);
        String text = bodyText(body);
        Map<String, Integer> applied = service.ingestReport(serverId, text, req.getRemoteAddr());
        StringBuilder sb = new StringBuilder("ok");
        applied.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        return text(sb.append('\n').toString());
    }

    @Operation(summary = "데몬 heartbeat + 웹 설정·제어 명령·로그 요청 받기")
    @GetMapping("/commands")
    public ResponseEntity<String> commands(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                           @RequestParam(value = "state", required = false) String state,
                                           @RequestParam(value = "pid", required = false) Integer pid,
                                           @RequestParam(value = "ver", required = false) String ver,
                                           HttpServletRequest req) {
        return text(service.pullCommands(auth(token, req), state, pid, ver));
    }

    @Operation(summary = "데몬 종료 보고")
    @PostMapping(value = "/daemon", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> daemon(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                         @RequestParam("state") String state, HttpServletRequest req) {
        Long serverId = auth(token, req);
        if (!"STOPPED".equals(state)) return ResponseEntity.badRequest().body("error: state must be STOPPED\n");
        service.daemonStopped(serverId);
        return text("ok\n");
    }

    @Operation(summary = "데몬 중단 중 프로세스 현황 (하트비트 감시자가 대신 수집, 데몬이 멈춰 있을 때만 받음)")
    @PostMapping(value = "/fallback", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> fallback(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                           @RequestBody(required = false) byte[] body, HttpServletRequest req) {
        Long serverId = auth(token, req);
        return fallback.ingest(serverId, bodyText(body)) ? text("ok\n")
                : ResponseEntity.status(HttpStatus.CONFLICT).body("ignored: daemon is running\n");
    }

    @Operation(summary = "제어 명령 실행 결과 보고")
    @PostMapping(value = "/commands/{cmdId}/result", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> commandResult(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                @PathVariable("cmdId") Long cmdId, @RequestParam("exit") int exit,
                                                @RequestBody(required = false) byte[] body, HttpServletRequest req) {
        Long serverId = auth(token, req);
        boolean ok = service.commandResult(serverId, cmdId, exit, bodyText(body));
        return ok ? text("ok\n") : ResponseEntity.status(HttpStatus.CONFLICT).body("ignored: command not in SENT state\n");
    }

    @Operation(summary = "요청받은 서버 로그 올리기")
    @PostMapping(value = "/logs/{logId}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> logUpload(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                            @PathVariable("logId") Long logId,
                                            @RequestParam(value = "status", defaultValue = "OK") String status,
                                            @RequestBody(required = false) byte[] body, HttpServletRequest req) {
        Long serverId = auth(token, req);
        boolean ok = serverLogService.ingestLog(serverId, logId, status, bodyText(body));
        return ok ? text("ok\n") : ResponseEntity.status(HttpStatus.NOT_FOUND).body("ignored: unknown log id\n");
    }

    // ---- 내부 ----

    private Long auth(String token, HttpServletRequest req) {
        Long serverId = service.authenticate(token == null ? null : token.trim());
        if (serverId == null) {
            log.warn("AGENT AUTH FAIL from {} {}", req.getRemoteAddr(), req.getRequestURI());
            throw new AgentAuthException();
        }
        return serverId;
    }

    private String bodyText(byte[] body) {
        if (body == null) return "";
        if (body.length > props.getMaxReportBytes()) throw TopologyException.badRequest("report too large");
        return new String(body, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<String> text(String s) {
        return ResponseEntity.ok(s);
    }

    static class AgentAuthException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @ExceptionHandler(AgentAuthException.class)
    public ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("error: invalid agent token\n");
    }

    @ExceptionHandler(TopologyException.class)
    public ResponseEntity<String> topology(TopologyException e) {
        return ResponseEntity.status(e.getStatus()).body("error: " + e.getMessage() + "\n");
    }
}
