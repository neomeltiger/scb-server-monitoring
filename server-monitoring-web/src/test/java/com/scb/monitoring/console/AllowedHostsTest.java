package com.scb.monitoring.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AllowedHostsTest {

    private final AllowedHosts hosts = new AllowedHosts(Arrays.asList(
            "10.0.13.217", "10.62.0.0/16", "Jump01.Example.com"));

    @Test
    void exactIp() {
        assertTrue(hosts.isAllowed("10.0.13.217"));
        assertFalse(hosts.isAllowed("10.0.13.218"));
    }

    @Test
    void cidrRange() {
        assertTrue(hosts.isAllowed("10.62.0.1"));
        assertTrue(hosts.isAllowed("10.62.255.254"));
        assertFalse(hosts.isAllowed("10.63.0.1"));
    }

    @Test
    void hostnameIgnoresCase() {
        assertTrue(hosts.isAllowed("jump01.example.com"));
        assertTrue(hosts.isAllowed(" JUMP01.EXAMPLE.COM "));
        assertFalse(hosts.isAllowed("jump02.example.com"));
    }

    @Test
    void rejectsMalformed() {
        assertFalse(hosts.isAllowed(""));
        assertFalse(hosts.isAllowed(null));
        assertFalse(hosts.isAllowed("10.0.13.999"));
        assertFalse(hosts.isAllowed("evil..com"));
        assertFalse(hosts.isAllowed("10.62.0.1;rm"));
        assertFalse(AllowedHosts.isValidHost("300.1.1.1"));
        assertTrue(AllowedHosts.isValidHost("10.1.2.3"));
    }
}
