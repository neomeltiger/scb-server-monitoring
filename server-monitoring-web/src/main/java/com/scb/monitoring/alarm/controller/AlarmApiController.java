package com.scb.monitoring.alarm.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.scb.monitoring.alarm.dto.AlarmView;
import com.scb.monitoring.alarm.service.AlarmService;

/**
 * 서버 장애 알람 (메인 홈 경고 배너).
 * <pre>
 * GET  /api/alarms                 진행 중(active) + 최근 해소(recentClosed)
 * POST /api/alarms/{id}/ack        {"memo":"조치 내용"}  - ADMIN
 * POST /api/alarms/check           즉시 점검 - ADMIN
 * </pre>
 */
@Tag(name = "1. 알람", description = "서버 장애 알람 (서버 Down / 보고 끊김)")
@RestController
@RequestMapping("/api/alarms")
public class AlarmApiController {

    private final AlarmService service;

    public AlarmApiController(AlarmService service) {
        this.service = service;
    }

    @Operation(summary = "알람 목록 (진행 중 + 최근 해소)")
    @GetMapping
    public Map<String, Object> list() {
        List<AlarmView> active = service.getActive();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", active);
        body.put("criticalCount", active.stream().filter(a -> "CRITICAL".equals(a.getAlarmLevel())).count());
        body.put("openCount", active.stream().filter(a -> "OPEN".equals(a.getStateCd())).count());
        body.put("recentClosed", service.getRecentClosed());
        return body;
    }

    @Operation(summary = "알람 확인 처리 - ADMIN")
    @PostMapping("/{alarmId}/ack")
    public AlarmView ack(@PathVariable("alarmId") Long alarmId, @RequestBody(required = false) Map<String, Object> req,
                         Authentication auth) {
        Object memo = req == null ? null : req.get("memo");
        return service.acknowledge(alarmId, auth.getName(), memo == null ? null : String.valueOf(memo));
    }

    @Operation(summary = "즉시 점검 - ADMIN")
    @PostMapping("/check")
    public Map<String, Object> check() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("opened", service.checkAll());
        body.put("message", "알람 점검을 완료했습니다.");
        return body;
    }
}
