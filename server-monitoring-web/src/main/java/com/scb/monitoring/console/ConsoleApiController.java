package com.scb.monitoring.console;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹 콘솔 화면(static/js/web_console.js)이 접속 모드를 확인하는 API */
@RestController
public class ConsoleApiController {

    private final ConsoleProperties props;

    public ConsoleApiController(ConsoleProperties props) {
        this.props = props;
    }

    @GetMapping("/api/console-config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", props.isDemo() ? "demo" : "live");
        body.put("path", "ssh"); // 화면 기준 상대 경로 (컨텍스트 경로가 있어도 동작)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
