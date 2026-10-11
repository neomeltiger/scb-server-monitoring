package com.scb.monitoring.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 웹 콘솔 WebSocket(/ssh) 등록.
 * - demo 모드에서는 등록하지 않습니다.
 * - 로그인(Spring Security)한 사용자만 연결됩니다.
 * - allowed-origins 를 비우면 이 서버와 같은 주소에서 연 화면만 허용합니다.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final ConsoleProperties props;
    private final AllowedHosts allowedHosts;

    public WebSocketConfig(ConsoleProperties props, AllowedHosts allowedHosts) {
        this.props = props;
        this.allowedHosts = allowedHosts;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (props.isDemo()) {
            log.info("웹 콘솔: 데모 모드 (실제 SSH 접속 없음)");
            return;
        }
        WebSocketHandlerRegistration reg = registry.addHandler(new SshWebSocketHandler(props, allowedHosts), "/ssh");
        if (!props.getAllowedOrigins().isEmpty()) {
            reg.setAllowedOrigins(props.getAllowedOrigins().toArray(new String[0]));
        }
        log.info("웹 콘솔: 실제 SSH 접속 모드 (WebSocket /ssh, 허용 서버 목록: {})", props.getAllowedHostsFile());
        if ("no".equalsIgnoreCase(props.getSsh().getStrictHostKeyChecking())) {
            log.warn("SSH 호스트 키 검사가 꺼져 있습니다. 운영 시 app.console.ssh.strict-host-key-checking=yes 와 known-hosts-file 설정을 권장합니다.");
        }
    }
}
