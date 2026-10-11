package com.scb.monitoring.console;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 웹 콘솔 WebSocket(/ssh) ↔ SSH 중계.
 * <pre>
 * 접속  : /ssh?host=IP&port=22&user=계정&cols=120&rows=30
 * 수신  : {"type":"data","data":"키 입력"} / {"type":"resize","cols":N,"rows":N}
 * 송신  : 터미널 출력 (바이너리 UTF-8, 안내 문구는 텍스트)
 * </pre>
 * 비밀번호는 터미널에서 입력받아 메모리에서만 사용하며 로그에 남기지 않습니다.
 * 접속 기록은 'console-audit' 로거로 남깁니다. (웹 로그인 사용자, 접속 대상, 결과)
 */
public class SshWebSocketHandler extends TextWebSocketHandler {

    static final Logger audit = LoggerFactory.getLogger("console-audit");
    private static final Pattern USER = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]{0,31}$");
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 1024 * 1024;

    private final ConsoleProperties props;
    private final AllowedHosts allowedHosts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SshBridge> bridges = new ConcurrentHashMap<>();

    public SshWebSocketHandler(ConsoleProperties props, AllowedHosts allowedHosts) {
        this.props = props;
        this.allowedHosts = allowedHosts;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession ws = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        MultiValueMap<String, String> q = UriComponentsBuilder.fromUri(raw.getUri()).build().getQueryParams();
        String host = param(q, "host").trim();
        String user = param(q, "user").trim();
        int port = toInt(param(q, "port"), 22);
        int cols = clamp(toInt(param(q, "cols"), 120), 20, 500);
        int rows = clamp(toInt(param(q, "rows"), 30), 5, 200);
        String webUser = raw.getPrincipal() != null ? raw.getPrincipal().getName() : "-";
        String client = raw.getRemoteAddress() != null ? raw.getRemoteAddress().getAddress().getHostAddress() : "-";
        String tag = "[" + webUser + "@" + client + "] " + user + "@" + host + ":" + port;

        if (!allowedHosts.isAllowed(host)) {
            audit.warn("{} REJECT host not allowed", tag);
            ws.sendMessage(new TextMessage("\r\n\u001b[31m접속이 허용되지 않은 서버입니다: " + host + "\u001b[0m\r\n"));
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("허용되지 않은 서버"));
            return;
        }
        if (!USER.matcher(user).matches() || port < 1 || port > 65535) {
            audit.warn("{} REJECT bad user/port", tag);
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("잘못된 접속 정보"));
            return;
        }

        audit.info("{} OPEN", tag);
        SshBridge bridge = new SshBridge(ws, host, port, user, cols, rows, props.getSsh(), tag);
        bridges.put(raw.getId(), bridge);
        bridge.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        SshBridge bridge = bridges.get(raw.getId());
        if (bridge == null) return;
        JsonNode msg;
        try {
            msg = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            return;
        }
        String type = msg.path("type").asText("");
        if ("resize".equals(type)) {
            bridge.resize(clamp(msg.path("cols").asInt(120), 20, 500), clamp(msg.path("rows").asInt(30), 5, 200));
        } else if ("data".equals(type) && msg.path("data").isTextual()) {
            bridge.input(msg.path("data").asText());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        SshBridge bridge = bridges.remove(raw.getId());
        if (bridge != null) bridge.close();
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        SshBridge bridge = bridges.remove(raw.getId());
        if (bridge != null) bridge.close();
    }

    private static String param(MultiValueMap<String, String> q, String name) {
        String v = q.getFirst(name);
        return v == null ? "" : UriUtils.decode(v, StandardCharsets.UTF_8);
    }

    private static int toInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
