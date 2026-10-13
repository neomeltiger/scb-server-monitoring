package com.scb.monitoring.topology.controller;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.scb.monitoring.topology.service.TopologyException;

/** /api/topology, /api/services, /api/agents, /api/alarms, /api/admin/users, /api/admin/menus 오류 응답 : {"message": "..."} (화면에서 alert 로 표시) */
@RestControllerAdvice(assignableTypes = {TopologyApiController.class,
        com.scb.monitoring.auth.controller.UserAdminController.class,
        com.scb.monitoring.alarm.controller.AlarmApiController.class,
        com.scb.monitoring.serverlog.controller.ServerLogController.class,
        com.scb.monitoring.svcctl.controller.ServiceCtlApiController.class,
        com.scb.monitoring.agent.controller.AgentAdminController.class,
        com.scb.monitoring.menu.web.MenuController.class,
        com.scb.monitoring.settings.SettingsController.class})
public class TopologyApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TopologyApiExceptionHandler.class);

    @ExceptionHandler(TopologyException.class)
    public ResponseEntity<Map<String, String>> topology(TopologyException e) {
        return body(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        return body(HttpStatus.BAD_REQUEST, fe != null ? fe.getDefaultMessage() : "입력값이 올바르지 않습니다.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        log.warn("DB 제약 위반: {}", e.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "저장할 수 없습니다. (중복 또는 참조 데이터 확인)");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> db(DataAccessException e) {
        log.error("DB 오류", e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "데이터베이스 처리 중 오류가 발생했습니다. (MySQL 연결 확인)");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("message", message));
    }
}
