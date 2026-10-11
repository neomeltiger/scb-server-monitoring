package com.scb.monitoring.agent.controller;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * client.env 의 SCB_WEB_URL (서버 에이전트가 접속할 웹 주소) 결정.
 * <ol>
 *   <li>app.agent.public-url 이 있으면 그대로 사용</li>
 *   <li>없으면 화면을 연 주소. 단 localhost / 127.x 로 열었으면 서버에서는 서버 자신을 가리키므로
 *       이 PC 의 네트워크 IPv4 로 바꾸고 경고 (가상 어댑터는 뒤로)</li>
 * </ol>
 */
final class AgentWebUrl {

    private final String url;
    private final String warning;
    private final List<String> candidates;

    private AgentWebUrl(String url, String warning, List<String> candidates) {
        this.url = url;
        this.warning = warning;
        this.candidates = candidates;
    }

    String getUrl() { return url; }
    String getWarning() { return warning; }
    List<String> getCandidates() { return candidates; }

    static AgentWebUrl resolve(String publicUrl, String requestUrl) {
        if (publicUrl != null && !publicUrl.trim().isEmpty()) {
            return new AgentWebUrl(publicUrl.trim().replaceAll("/+$", ""), null, Collections.<String>emptyList());
        }
        URI u = URI.create(requestUrl);
        if (!isLoopback(u.getHost())) {
            return new AgentWebUrl(requestUrl, null, Collections.<String>emptyList());
        }
        List<String> ips = localIpv4();
        List<String> urls = new ArrayList<>();
        for (String ip : ips) urls.add(replaceHost(u, ip));
        if (urls.isEmpty()) {
            return new AgentWebUrl(requestUrl,
                    "화면을 localhost 로 열어 주소가 localhost 입니다. 서버에서 접속 가능한 이 웹 서버의 IP 로 SCB_WEB_URL 을 바꾸세요.",
                    urls);
        }
        return new AgentWebUrl(urls.get(0),
                "화면을 localhost 로 열어서 SCB_WEB_URL 을 이 PC 의 IP(" + ips.get(0) + ")로 바꿔 표시했습니다. "
                        + "서버에서 이 주소로 접속되는지 확인하세요. (항상 같은 주소를 쓰려면 application.yml 의 app.agent.public-url 지정)",
                urls);
    }

    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    private static String replaceHost(URI u, String host) {
        StringBuilder sb = new StringBuilder(u.getScheme()).append("://").append(host);
        if (u.getPort() > 0) sb.append(':').append(u.getPort());
        if (u.getRawPath() != null) sb.append(u.getRawPath());
        return sb.toString().replaceAll("/+$", "");
    }

    /** 사용 중인 IPv4 (루프백 제외). 실제 어댑터 먼저, Hyper-V/VMware/VirtualBox 등 가상 어댑터는 뒤 */
    private static List<String> localIpv4() {
        List<String> real = new ArrayList<>();
        List<String> virtual = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                boolean virt = ni.isVirtual() || name.contains("vethernet") || name.contains("virtual")
                        || name.contains("vmware") || name.contains("hyper-v") || name.contains("docker") || name.contains("wsl");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    (virt ? virtual : real).add(a.getHostAddress());
                }
            }
        } catch (SocketException e) {
            return Collections.emptyList();
        }
        real.addAll(virtual);
        return real;
    }
}
