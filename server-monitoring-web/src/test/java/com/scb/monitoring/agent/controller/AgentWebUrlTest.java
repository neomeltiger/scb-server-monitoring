package com.scb.monitoring.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** client.env 의 SCB_WEB_URL : 서버에서 localhost 는 서버 자신이므로 그대로 내보내지 않음 */
class AgentWebUrlTest {

    @Test
    void publicUrlWins() {
        AgentWebUrl u = AgentWebUrl.resolve("http://10.0.1.20:8080/server-monitoring-web/", "http://localhost:8081");
        assertEquals("http://10.0.1.20:8080/server-monitoring-web", u.getUrl());
        assertNull(u.getWarning());
    }

    @Test
    void networkAddressIsKept() {
        AgentWebUrl u = AgentWebUrl.resolve("", "http://192.168.55.43:8080/server-monitoring-web");
        assertEquals("http://192.168.55.43:8080/server-monitoring-web", u.getUrl());
        assertNull(u.getWarning());
    }

    @Test
    void localhostIsReplacedOrWarned() {
        AgentWebUrl u = AgentWebUrl.resolve(null, "http://localhost:8081");
        assertNotNull(u.getWarning(), "localhost 면 항상 경고");
        if (!u.getCandidates().isEmpty()) {
            assertFalse(u.getUrl().contains("localhost"));
            assertTrue(u.getUrl().endsWith(":8081"));
        }
    }

    @Test
    void loopbackHosts() {
        assertTrue(AgentWebUrl.isLoopback("localhost"));
        assertTrue(AgentWebUrl.isLoopback("127.0.0.1"));
        assertTrue(AgentWebUrl.isLoopback("[::1]"));
        assertFalse(AgentWebUrl.isLoopback("10.0.13.217"));
        assertFalse(AgentWebUrl.isLoopback("localhost.example.com"));
    }
}
