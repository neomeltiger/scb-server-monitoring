package com.scb.monitoring.topology.service;

import org.springframework.http.HttpStatus;

/** 네트워크 구성도 업무 오류 (화면에 message 를 그대로 보여줌) */
public class TopologyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public TopologyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static TopologyException notFound(String message) {
        return new TopologyException(HttpStatus.NOT_FOUND, message);
    }

    public static TopologyException badRequest(String message) {
        return new TopologyException(HttpStatus.BAD_REQUEST, message);
    }

    public static TopologyException conflict(String message) {
        return new TopologyException(HttpStatus.CONFLICT, message);
    }

    public HttpStatus getStatus() { return status; }
}
