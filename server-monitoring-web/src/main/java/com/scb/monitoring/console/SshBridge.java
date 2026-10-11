package com.scb.monitoring.console;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * WebSocket 세션 1개 ↔ SSH 셸 1개 연결.
 * 단계: PASSWORD(비밀번호 입력) → CONNECTING → SHELL → CLOSED
 */
class SshBridge {

    private enum Stage { PASSWORD, CONNECTING, SHELL, CLOSED }

    /**
     * pty 요청 시 보내는 터미널 모드 (RFC 4254 8장, PuTTY 기본값과 같은 방식).
     * 보내지 않으면 서버 기본값을 쓰는데, Solaris Sun_SSH 등은 ICRNL(입력 CR→LF 변환)이 꺼져 있어
     * 브라우저 Enter(CR)가 줄 끝으로 인식되지 않고 read 를 쓰는 메뉴(scb_menu.sh 등)가 멈춥니다.
     */
    static final byte[] TERMINAL_MODES = terminalModes(
            1, 3,      // VINTR   ^C
            2, 28,     // VQUIT   ^\
            3, 127,    // VERASE  DEL (브라우저 Backspace)
            4, 21,     // VKILL   ^U
            5, 4,      // VEOF    ^D
            10, 26,    // VSUSP   ^Z
            36, 1,     // ICRNL   입력 CR → LF
            38, 0,     // IXON    ^S 화면 정지 끔
            50, 1,     // ISIG
            51, 1,     // ICANON  줄 단위 입력
            53, 1,     // ECHO
            54, 1,     // ECHOE
            55, 1,     // ECHOK
            59, 1,     // IEXTEN
            70, 1,     // OPOST
            72, 1,     // ONLCR   출력 LF → CRLF
            91, 1,     // CS8
            128, 38400, // TTY_OP_ISPEED
            129, 38400  // TTY_OP_OSPEED
    );

    /** (opcode, 값) 쌍 → opcode 1바이트 + uint32 값, 끝에 TTY_OP_END(0) */
    private static byte[] terminalModes(int... pairs) {
        ByteBuffer b = ByteBuffer.allocate(pairs.length / 2 * 5 + 1);
        for (int i = 0; i < pairs.length; i += 2) {
            b.put((byte) pairs[i]);
            b.putInt(pairs[i + 1]);
        }
        b.put((byte) 0);
        return b.array();
    }

    private final WebSocketSession ws;
    private final String host;
    private final int port;
    private final String user;
    private final ConsoleProperties.Ssh cfg;
    private final String tag;

    private volatile Stage stage = Stage.PASSWORD;
    private volatile int cols;
    private volatile int rows;
    private final StringBuilder password = new StringBuilder();
    private int tries;
    private Session session;
    private ChannelShell channel;
    private OutputStream toRemote;

    SshBridge(WebSocketSession ws, String host, int port, String user, int cols, int rows,
              ConsoleProperties.Ssh cfg, String tag) {
        this.ws = ws;
        this.host = host;
        this.port = port;
        this.user = user;
        this.cols = cols;
        this.rows = rows;
        this.cfg = cfg;
        this.tag = tag;
    }

    void start() {
        askPassword();
    }

    /** 브라우저 키 입력 */
    synchronized void input(String data) {
        if (stage == Stage.SHELL && toRemote != null) {
            try {
                toRemote.write(data.getBytes(StandardCharsets.UTF_8));
                toRemote.flush();
            } catch (IOException e) {
                closeWs(CloseStatus.SERVER_ERROR.withReason("전송 오류"));
            }
            return;
        }
        if (stage != Stage.PASSWORD) return;
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '\r' || ch == '\n') {
                say("\r\n");
                stage = Stage.CONNECTING;
                final String pw = password.toString();
                password.setLength(0);
                Thread t = new Thread(() -> connect(pw), "ssh-connect-" + host);
                t.setDaemon(true);
                t.start();
                return;
            }
            if (ch == '\u0003') { // Ctrl+C
                say("^C\r\n");
                closeWs(CloseStatus.NORMAL.withReason("사용자 취소"));
                return;
            }
            if (ch == '\u007f' || ch == '\b') {
                if (password.length() > 0) password.setLength(password.length() - 1);
            } else if (ch >= ' ') {
                password.append(ch);
            }
        }
    }

    synchronized void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        if (channel != null) channel.setPtySize(cols, rows, 0, 0);
    }

    synchronized void close() {
        if (stage == Stage.CLOSED) return;
        stage = Stage.CLOSED;
        password.setLength(0);
        if (channel != null) channel.disconnect();
        if (session != null) session.disconnect();
        SshWebSocketHandler.audit.info("{} CLOSE", tag);
    }

    private void askPassword() {
        say(user + "@" + host + "'s password: ");
    }

    private void connect(String pw) {
        synchronized (this) {
            if (stage == Stage.CLOSED) return;
            tries++;
        }
        Session s = null;
        try {
            JSch jsch = new JSch();
            if (cfg.getKnownHostsFile() != null && !cfg.getKnownHostsFile().trim().isEmpty()) {
                jsch.setKnownHosts(cfg.getKnownHostsFile().trim());
            }
            s = jsch.getSession(user, host, port);
            s.setPassword(pw);
            s.setUserInfo(new PasswordUserInfo(pw));
            s.setConfig("StrictHostKeyChecking", cfg.getStrictHostKeyChecking());
            s.setConfig("PreferredAuthentications", "keyboard-interactive,password");
            if (cfg.isLegacyAlgorithms()) {
                allowLegacyAlgorithms(s);
            }
            s.setServerAliveInterval(cfg.getKeepAliveMs());
            s.connect(cfg.getConnectTimeoutMs());

            ChannelShell ch = (ChannelShell) s.openChannel("shell");
            ch.setPtyType(cfg.getTermType(), cols, rows, 0, 0);
            ch.setTerminalMode(TERMINAL_MODES);
            InputStream fromRemote = ch.getInputStream();
            OutputStream out = ch.getOutputStream();
            // connect(timeout) 은 채널 열림 응답이 먼저 도착하면 timeout 만큼 기다리는 문제가 있어
            // 짧은 간격으로 확인하는 connect() 를 사용합니다. (세션 접속 자체의 timeout 은 위 s.connect 에서 적용)
            ch.connect();

            synchronized (this) {
                if (stage == Stage.CLOSED) {
                    ch.disconnect();
                    s.disconnect();
                    return;
                }
                session = s;
                channel = ch;
                toRemote = out;
                stage = Stage.SHELL;
                ch.setPtySize(cols, rows, 0, 0); // 접속 중 바뀐 창 크기 반영
            }
            SshWebSocketHandler.audit.info("{} AUTH OK ({})", tag, s.getServerVersion());
            pump(fromRemote);
        } catch (JSchException e) {
            if (s != null) s.disconnect();
            onConnectError(e);
        } catch (IOException e) {
            if (s != null) s.disconnect();
            onConnectError(e);
        }
    }

    /**
     * 구형 SSH 서버 호환 알고리즘을 각 목록의 맨 끝에 추가합니다.
     * SSH 는 클라이언트 목록 순서대로 서버와 공통인 첫 알고리즘을 고르므로,
     * 최신 서버는 기존처럼 최신 알고리즘으로 접속하고 구형 서버만 아래 알고리즘을 사용합니다.
     * (예: Solaris 10 Sun_SSH_1.1.5 - diffie-hellman-group*-sha1, ssh-rsa, aes*-cbc, hmac-sha1)
     */
    private static void allowLegacyAlgorithms(Session s) {
        appendConfig(s, "kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1");
        appendConfig(s, "server_host_key", "ssh-rsa,ssh-dss");
        String ciphers = "aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc";
        appendConfig(s, "cipher.s2c", ciphers);
        appendConfig(s, "cipher.c2s", ciphers);
        String macs = "hmac-sha1,hmac-sha1-96,hmac-md5";
        appendConfig(s, "mac.s2c", macs);
        appendConfig(s, "mac.c2s", macs);
    }

    private static void appendConfig(Session s, String key, String extra) {
        String current = s.getConfig(key);
        Set<String> merged = new LinkedHashSet<>();
        if (current != null && !current.isEmpty()) merged.addAll(Arrays.asList(current.split(",")));
        merged.addAll(Arrays.asList(extra.split(",")));
        s.setConfig(key, String.join(",", merged));
    }

    /** SSH 출력 → 브라우저 */
    private void pump(InputStream in) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n > 0 && ws.isOpen()) ws.sendMessage(new BinaryMessage(ByteBuffer.wrap(Arrays.copyOf(buf, n))));
            }
        } catch (IOException e) {
            // 연결 종료 시 발생 - 아래에서 정리
        }
        closeWs(CloseStatus.NORMAL.withReason("원격 세션 종료"));
    }

    private void onConnectError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        boolean authFail = msg.contains("Auth fail") || msg.contains("Auth cancel");
        SshWebSocketHandler.audit.warn("{} ERROR {}", tag, msg);
        synchronized (this) {
            if (stage == Stage.CLOSED) return;
            if (authFail && tries < cfg.getMaxPasswordTries() && ws.isOpen()) {
                stage = Stage.PASSWORD;
                say("Permission denied, please try again.\r\n");
                askPassword();
                return;
            }
        }
        say("\r\n\u001b[31m접속 실패: " + (authFail ? "인증 실패 (Permission denied)" : msg) + "\u001b[0m\r\n");
        closeWs(authFail ? CloseStatus.SERVER_ERROR.withReason("인증 실패") : CloseStatus.SERVER_ERROR.withReason("접속 실패"));
    }

    private void say(String text) {
        try {
            if (ws.isOpen()) ws.sendMessage(new TextMessage(text));
        } catch (IOException e) {
            // 브라우저 연결이 끊긴 경우 무시
        }
    }

    private void closeWs(CloseStatus status) {
        try {
            if (ws.isOpen()) ws.close(status);
        } catch (IOException e) {
            // 무시
        }
        close();
    }

    /** 비밀번호 / keyboard-interactive 인증에 같은 비밀번호로 응답 */
    private static class PasswordUserInfo implements UserInfo, UIKeyboardInteractive {
        private final String pw;

        PasswordUserInfo(String pw) { this.pw = pw; }

        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return pw; }
        @Override public boolean promptPassword(String message) { return true; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            String[] answers = new String[prompt.length];
            Arrays.fill(answers, pw);
            return answers;
        }
    }
}
