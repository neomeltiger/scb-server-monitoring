package com.scb.monitoring.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SshBridgeTerminalModesTest {

    /** RFC 4254 형식(opcode 1바이트 + uint32)을 다시 읽어 확인 */
    private static Map<Integer, Integer> decode(byte[] modes) {
        Map<Integer, Integer> m = new HashMap<>();
        ByteBuffer b = ByteBuffer.wrap(modes);
        while (true) {
            int op = b.get() & 0xFF;
            if (op == 0) break;
            m.put(op, b.getInt());
        }
        assertEquals(0, b.remaining(), "TTY_OP_END 뒤에 남은 데이터가 없어야 함");
        return m;
    }

    @Test
    void sendsModesNeededForLineInput() {
        Map<Integer, Integer> m = decode(SshBridge.TERMINAL_MODES);
        assertEquals(1, m.get(36));      // ICRNL : Enter(CR) -> LF
        assertEquals(1, m.get(51));      // ICANON
        assertEquals(1, m.get(53));      // ECHO
        assertEquals(1, m.get(72));      // ONLCR
        assertEquals(127, m.get(3));     // VERASE = DEL
        assertEquals(3, m.get(1));       // VINTR = ^C
        assertEquals(38400, m.get(128)); // ISPEED
    }
}
