package com.scb.monitoring.console;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 웹 콘솔 접속 허용 서버 목록 (config/allowed-hosts.json).
 * <pre>
 * [ "10.0.13.217",            IP 주소
 *   "10.0.0.0/16",            IP 대역 (CIDR) - 화면에서 주소를 직접 입력해 접속할 대역
 *   "host.example.com" ]       호스트명 (대소문자 무시)
 * </pre>
 * 파일이 바뀌면 재시작 없이 다음 접속부터 반영됩니다.
 */
@Component
public class AllowedHosts {

    private static final Logger log = LoggerFactory.getLogger(AllowedHosts.class);
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern HOSTNAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<String> entries = Collections.emptyList();
    private volatile long loadedModified = -1;

    @Autowired
    public AllowedHosts(ConsoleProperties props) {
        this.file = new File(props.getAllowedHostsFile());
        reloadIfChanged();
    }

    /** 테스트용: 목록을 직접 지정 */
    AllowedHosts(List<String> list) {
        this.file = null;
        this.entries = normalize(list);
    }

    public boolean isAllowed(String host) {
        reloadIfChanged();
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        Long ip = ipToLong(h);
        if (ip == null && !isValidHostname(h)) return false;
        for (String entry : entries) {
            if (entry.contains("/")) {
                if (ip != null && inCidr(ip, entry)) return true;
            } else if (entry.equals(h)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidHostname(String h) {
        return h != null && h.length() <= 253 && !h.matches("^[\\d.]+$") && HOSTNAME.matcher(h).matches();
    }

    public static boolean isValidHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return ipToLong(h) != null || isValidHostname(h);
    }

    static Long ipToLong(String ip) {
        Matcher m = IPV4.matcher(ip);
        if (!m.matches()) return null;
        long v = 0;
        for (int i = 1; i <= 4; i++) {
            int part = Integer.parseInt(m.group(i));
            if (part > 255) return null;
            v = (v << 8) | part;
        }
        return v;
    }

    private static boolean inCidr(long ip, String cidr) {
        String[] parts = cidr.split("/", 2);
        Long base = ipToLong(parts[0]);
        int bits;
        try {
            bits = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (base == null || bits < 0 || bits > 32) return false;
        long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return (ip & mask) == (base & mask);
    }

    private synchronized void reloadIfChanged() {
        if (file == null) return;
        if (!file.isFile()) {
            if (loadedModified != 0) log.warn("접속 허용 서버 목록 파일이 없습니다: {} (모든 접속 차단)", file.getAbsolutePath());
            entries = Collections.emptyList();
            loadedModified = 0;
            return;
        }
        long modified = file.lastModified();
        if (modified == loadedModified) return;
        try {
            List<String> list = mapper.readValue(file, new TypeReference<List<String>>() { });
            entries = normalize(list);
            loadedModified = modified;
            log.info("접속 허용 서버 목록 로드: {} ({}건)", file.getAbsolutePath(), entries.size());
        } catch (Exception e) {
            log.error("접속 허용 서버 목록을 읽지 못했습니다. 이전 목록을 유지합니다: {}", e.getMessage());
            loadedModified = modified;
        }
    }

    private static List<String> normalize(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (String s : list) {
                if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
