/*
 * ScbApiClient - 통합 서버 모니터링 웹 API 호출 샘플 (복사해서 바로 사용)
 *
 *  - 파일 하나, 외부 라이브러리 없음, JDK 1.8 이상
 *  - 필요하면 맨 위에 package 선언만 추가해서 프로젝트에 넣으세요.
 *  - 로그인(세션 쿠키), CSRF(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더), JSON 변환을 모두 처리합니다.
 *  - 각 API 의 호출 예제는 Swagger UI (/swagger-ui.html) 의 API 설명에 있습니다.
 *
 * 사용 예
 *   ScbApiClient api = new ScbApiClient("http://localhost:8080");        // Tomcat 배포면 http://host:8080/server-monitoring-web
 *   api.login("admin", System.getenv("SCB_API_PASSWORD"));             // 비밀번호는 코드에 쓰지 말고 환경변수 등으로
 *   Object servers = api.get("/api/topology/servers");                  // JSON → List / Map / String / Number / Boolean / null
 *   for (Object s : (List<?>) servers) System.out.println(ScbApiClient.field(s, "systemName"));
 *   api.put("/api/agents/11/heartbeat-interval", ScbApiClient.map("sec", 5));   // 변경 API 는 ADMIN 계정
 *   String pong = api.agentGet("/agent-api/v1/ping", agentToken);       // 에이전트 API (토큰, text/plain)
 *
 * 실행해 보기 (조회만 함)
 *   javac -encoding UTF-8 ScbApiClient.java
 *   set SCB_API_PASSWORD=비밀번호        (Solaris/Linux : export SCB_API_PASSWORD=비밀번호)
 *   java ScbApiClient http://localhost:8080 admin
 */
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScbApiClient {

    private final String baseUrl;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    private String user;
    private String password;

    /** @param baseUrl 웹 주소 (예: http://10.0.0.10:8080, Tomcat 배포면 http://10.0.0.10:8080/server-monitoring-web) */
    public ScbApiClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public ScbApiClient timeouts(int connectMs, int readMs) {
        this.connectTimeoutMs = connectMs;
        this.readTimeoutMs = readMs;
        return this;
    }

    // ================================================================ 로그인

    /** 웹 로그인. 실패하면 ApiException (status 401). 세션이 끊기면 다음 호출 때 자동으로 다시 로그인합니다. */
    public void login(String user, String password) throws IOException {
        if (password == null) throw new IllegalArgumentException("password is null (예: 환경변수 SCB_API_PASSWORD)");
        this.user = user;
        this.password = password;
        cookies.clear();
        send("GET", "/login", null, null, null);                        // 세션 + CSRF 쿠키 받기
        String form = "username=" + enc(user) + "&password=" + enc(password) + "&_csrf=" + enc(csrf());
        Response r = send("POST", "/login", form.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", null);
        String loc = r.header("Location");
        if (r.status != 302 || loc == null || loc.contains("/login")) {
            throw new ApiException(401, "로그인 실패 (" + (loc == null ? "status " + r.status : loc) + ")");
        }
    }

    public void logout() throws IOException {
        send("POST", "/logout", new byte[0], "application/x-www-form-urlencoded", null);
        cookies.clear();
    }

    // ================================================================ 웹 API (/api/**) - JSON

    public Object get(String path) throws IOException { return json("GET", path, null); }

    public Object post(String path, Object body) throws IOException { return json("POST", path, body); }

    public Object put(String path, Object body) throws IOException { return json("PUT", path, body); }

    public Object delete(String path) throws IOException { return json("DELETE", path, null); }

    /** JSON 이 아닌 응답 (예: GET /api/services/{serverId}/service-list) */
    public String getText(String path) throws IOException {
        Response r = call("GET", path, null, null);
        return r.text();
    }

    private Object json(String method, String path, Object body) throws IOException {
        byte[] data = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
        Response r = call(method, path, data, data == null ? null : "application/json");
        String t = r.text();
        return t.trim().isEmpty() ? null : Json.parse(t);
    }

    /** 세션이 끊겼으면(로그인 화면으로 이동) 한 번 다시 로그인하고 재시도 */
    private Response call(String method, String path, byte[] body, String contentType) throws IOException {
        Response r = send(method, path, body, contentType, null);
        if (isLoginRedirect(r) && user != null) {
            login(user, password);
            r = send(method, path, body, contentType, null);
        }
        if (isLoginRedirect(r)) throw new ApiException(401, "로그인이 필요합니다. login() 을 먼저 호출하세요.");
        if (r.status >= 400) throw new ApiException(r.status, errorMessage(r));
        return r;
    }

    private static boolean isLoginRedirect(Response r) {
        String loc = r.header("Location");
        return (r.status == 302 || r.status == 303) && loc != null && loc.contains("/login");
    }

    private static String errorMessage(Response r) {
        String t = r.text();
        try {
            Object o = Json.parse(t);
            Object m = field(o, "message");
            if (m != null) return String.valueOf(m);
        } catch (RuntimeException ignore) {
            // JSON 이 아닌 오류 화면
        }
        if (r.status == 403) return "권한이 없습니다 (변경 API 는 ADMIN 계정 필요)";
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    // ================================================================ 에이전트 API (/agent-api/**) - 토큰, text/plain

    public String agentGet(String path, String agentToken) throws IOException {
        return agentCall("GET", path, null, agentToken);
    }

    public String agentPost(String path, String text, String agentToken) throws IOException {
        return agentCall("POST", path, text == null ? "" : text, agentToken);
    }

    private String agentCall(String method, String path, String text, String token) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-SCB-Agent-Token", token);
        Response r = send(method, path, text == null ? null : text.getBytes(StandardCharsets.UTF_8),
                text == null ? null : "text/plain; charset=UTF-8", h);
        if (r.status >= 400) throw new ApiException(r.status, r.text().trim());
        return r.text();
    }

    // ================================================================ HTTP

    private Response send(String method, String path, byte[] body, String contentType, Map<String, String> headers)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookieHeader());
        if (!"GET".equals(method) && csrf() != null) c.setRequestProperty("X-XSRF-TOKEN", csrf());
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
        }
        Response r = new Response();
        r.status = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? new byte[0] : readAll(in);
        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) {
            for (String sc : setCookies) {
                String nv = sc.split(";", 2)[0];
                int eq = nv.indexOf('=');
                if (eq > 0) {
                    String name = nv.substring(0, eq).trim();
                    String value = nv.substring(eq + 1).trim();
                    if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) cookies.remove(name);
                    else cookies.put(name, value);
                }
            }
        }
        c.disconnect();
        return r;
    }

    private String csrf() { return cookies.get("XSRF-TOKEN"); }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String enc(String s) throws IOException { return s == null ? "" : URLEncoder.encode(s, "UTF-8"); }

    static class Response {
        int status;
        Map<String, List<String>> headers;
        byte[] body;

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    /** API 오류 (HTTP 상태 + 서버 메시지) */
    public static class ApiException extends IOException {
        private final int status;

        public ApiException(int status, String message) {
            super("HTTP " + status + " : " + message);
            this.status = status;
        }

        public int getStatus() { return status; }
    }

    // ================================================================ JSON 도우미

    /** 요청 본문 만들기 : map("sec", 5, "action", "start") → {"sec":5,"action":"start"} */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 응답에서 값 꺼내기 : field(server, "systemName") */
    public static Object field(Object jsonObject, String name) {
        return jsonObject instanceof Map ? ((Map<?, ?>) jsonObject).get(name) : null;
    }

    /** 최소 JSON 파서 / 출력 (Map = LinkedHashMap, 배열 = ArrayList, 숫자 = Long 또는 Double) */
    public static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        public static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("JSON 끝에 남은 문자 (위치 " + j.i + ")");
            return v;
        }

        public static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, -1, 0);
            return sb.toString();
        }

        /** 보기 좋게 (들여쓰기 2칸) */
        public static String pretty(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 2, 0);
            return sb.toString();
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return a;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }

        private Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("JSON 형식 오류 (위치 " + st + ")");
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("JSON 형식 오류 : '" + c + "' 필요 (위치 " + i + ")");
            i++;
        }

        private static void write(StringBuilder sb, Object v, int indent, int depth) {
            if (v == null) { sb.append("null"); return; }
            if (v instanceof String) { quote(sb, (String) v); return; }
            if (v instanceof Number || v instanceof Boolean) { sb.append(v); return; }
            if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    nl(sb, indent, depth + 1);
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(indent >= 0 ? ": " : ":");
                    write(sb, e.getValue(), indent, depth + 1);
                }
                nl(sb, indent, depth);
                sb.append('}');
                return;
            }
            if (v instanceof Iterable || v instanceof Object[]) {
                Iterable<?> it = v instanceof Object[] ? java.util.Arrays.asList((Object[]) v) : (Iterable<?>) v;
                if (!it.iterator().hasNext()) { sb.append("[]"); return; }
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) sb.append(',');
                    first = false;
                    nl(sb, indent, depth + 1);
                    write(sb, o, indent, depth + 1);
                }
                nl(sb, indent, depth);
                sb.append(']');
                return;
            }
            quote(sb, String.valueOf(v));
        }

        private static void nl(StringBuilder sb, int indent, int depth) {
            if (indent < 0) return;
            sb.append('\n');
            for (int k = 0; k < indent * depth; k++) sb.append(' ');
        }

        private static void quote(StringBuilder sb, String t) {
            sb.append('"');
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    // ================================================================ 실행 예 (조회 API 만 호출)

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        String user = args.length > 1 ? args[1] : "admin";
        String pw = System.getenv("SCB_API_PASSWORD");
        if (pw == null && System.console() != null) pw = new String(System.console().readPassword("%s 비밀번호: ", user));
        if (pw == null) {
            System.err.println("비밀번호를 환경변수 SCB_API_PASSWORD 로 지정하세요.");
            System.exit(2);
        }

        ScbApiClient api = new ScbApiClient(base);
        api.login(user, pw);
        System.out.println("로그인 : " + user + " @ " + base);

        // 서버 목록
        List<?> servers = (List<?>) api.get("/api/topology/servers");
        System.out.println("\n[서버 " + servers.size() + "대]  GET /api/topology/servers");
        for (Object s : servers) {
            System.out.println("  " + field(s, "id") + "  " + field(s, "systemName") + "  " + field(s, "ip") + "  " + field(s, "status")
                    + (field(s, "noData") != null ? "  (" + field(s, "noData") + ")" : ""));
        }

        // 하트비트 상태
        List<?> hb = (List<?>) api.get("/api/agents/heartbeat");
        System.out.println("\n[하트비트]  GET /api/agents/heartbeat");
        for (Object h : hb) {
            System.out.println("  " + field(h, "systemNm") + "  " + field(h, "hbState") + "  에이전트 " + field(h, "agentState")
                    + (field(h, "lastHbDt") != null ? "  마지막 수신 " + field(h, "lastHbDt") : ""));
        }

        // 진행 중 알람
        Object alarms = api.get("/api/alarms");
        System.out.println("\n[알람]  GET /api/alarms");
        System.out.println(Json.pretty(field(alarms, "active")));

        // 데몬 중단 프로세스 현황
        List<?> fb = (List<?>) api.get("/api/agents/fallback");
        System.out.println("\n[데몬 중단 프로세스 현황 " + fb.size() + "대]  GET /api/agents/fallback");

        api.logout();
    }
}
