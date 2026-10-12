package com.scb.monitoring.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * API 호출 예제 (Postman API 가이드처럼 cURL / Java / Postman).
 * <ul>
 *   <li>등록된 컨트롤러 매핑(/api/**, /agent-api/**, @Operation 이 있는 것)에서 자동으로 만듦 - API 가 늘면 예제도 같이 늘어남</li>
 *   <li>경로 값·쿼리·요청 본문 예시 : 이름별 예시 값 + 아래 BODY 표 (없으면 DTO 필드로 기본값)</li>
 *   <li>사용처 : Swagger UI 각 API 설명(OpenApiConfig), [Try it out] 본문 예시, Postman 컬렉션(ApiDocsController)</li>
 *   <li>Java 예제는 /samples/ScbApiClient.java (복사해서 쓰는 단일 파일 클라이언트) 기준</li>
 * </ul>
 */
@ConditionalOnProperty(prefix = "app.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ApiSampleCatalog {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}/]+)}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 경로 변수 예시 값 */
    private static final Map<String, Object> PATH_SAMPLE = new HashMap<>();
    /** 쿼리 예시 값 (필수가 아니어도 예제에 넣을 것) */
    private static final Map<String, Object> QUERY_SAMPLE = new LinkedHashMap<>();
    /** 요청 본문 예시 : "METHOD 경로" → JSON(Map/List) 또는 text */
    private static final Map<String, Object> BODY = new HashMap<>();
    /** 쿼리를 예제에 넣을 API : "METHOD 경로" → 이름 목록 */
    private static final Map<String, List<String>> QUERY_USE = new HashMap<>();

    static {
        PATH_SAMPLE.put("serverId", 11);
        PATH_SAMPLE.put("id", 11);
        PATH_SAMPLE.put("procId", 1);
        PATH_SAMPLE.put("svcId", 1);
        PATH_SAMPLE.put("alarmId", 1);
        PATH_SAMPLE.put("userId", "user01");
        PATH_SAMPLE.put("logId", 1);
        PATH_SAMPLE.put("snapId", 1);
        PATH_SAMPLE.put("cmdId", 1);

        QUERY_SAMPLE.put("points", 30);
        QUERY_SAMPLE.put("rows", 100);
        QUERY_SAMPLE.put("serverId", 11);
        QUERY_SAMPLE.put("status", "OK");
        QUERY_SAMPLE.put("state", "RUNNING");
        QUERY_SAMPLE.put("pid", 1234);
        QUERY_SAMPLE.put("ver", "1.9");
        QUERY_SAMPLE.put("daemon", "RUNNING");
        QUERY_SAMPLE.put("boot", "Y");
        QUERY_SAMPLE.put("hbver", "1.9");
        QUERY_SAMPLE.put("fb", "NONE");
        QUERY_SAMPLE.put("exit", 0);
        QUERY_SAMPLE.put("scriptHome", "/shared/opt/pos");
        // 같은 이름이라도 API 에 따라 다른 예시 : "METHOD 경로#이름"
        QUERY_SAMPLE.put("GET /api/admin/users#status", "PENDING");

        QUERY_USE.put("GET /api/topology/history", list("points"));
        QUERY_USE.put("GET /api/agents/heartbeat/events", list("serverId", "rows"));
        QUERY_USE.put("GET /api/admin/users", list("status"));
        QUERY_USE.put("GET /agent-api/v1/commands", list("state", "pid", "ver"));
        QUERY_USE.put("GET /agent-api/v1/heartbeat", list("pid", "daemon", "boot", "hbver", "fb"));
        QUERY_USE.put("POST /agent-api/v1/logs/{logId}", list("status"));

        Map<String, Object> server = obj("systemName", "MUREX TEST AP #1", "fqdn", "testap01.prod.example.com", "os", "SOLARIS",
                "ip", "10.0.99.10", "switchNodeId", 10, "status", "Active");
        BODY.put("POST /api/topology/servers", server);
        BODY.put("PUT /api/topology/servers/{id}", server);
        Map<String, Object> proc = obj("name", "mx_kernel_engine", "path", "/opt/murex/bin/mx_kernel", "running", true);
        BODY.put("POST /api/topology/servers/{id}/processes", proc);
        BODY.put("PUT /api/topology/servers/{id}/processes/{procId}", proc);
        Map<String, Object> svc = obj("group", 2, "level", 2, "name", "SCB_fs_app1", "account", "murex",
                "script", "/shared/opt/pos/SCB_fs_app1/mxg_fs.sh", "arg", null,
                "cmds", Arrays.asList("start", "stop", "test", "kill", "clean"), "scriptHome", "/shared/opt/pos");
        BODY.put("POST /api/services/{serverId}/items", svc);
        BODY.put("PUT /api/services/{serverId}/items/{svcId}", svc);
        BODY.put("POST /api/services/{serverId}/commands", obj("target", "SCB_fs_app1", "cmd", "status"));
        BODY.put("POST /api/services/{serverId}/import", obj("text",
                "#idx|GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT|ARG|CMDS\n1|2|2|SCB_fs_app1|murex|SCB_fs_app1/mxg_fs.sh||start|stop|test|kill|clean\n",
                "scriptHome", "/shared/opt/pos"));
        BODY.put("PUT /api/agents/{serverId}/heartbeat-interval", obj("sec", 5));
        BODY.put("PUT /api/agents/{serverId}/collect-interval", obj("sec", 60));
        BODY.put("POST /api/agents/{serverId}/daemon", obj("action", "start"));
        BODY.put("POST /api/alarms/{alarmId}/ack", obj("memo", "서버 재기동 후 정상 확인"));
        BODY.put("POST /api/admin/users/{userId}/approve", obj("roleCd", "USER"));
        BODY.put("POST /api/admin/users/{userId}/reject", obj("reason", "부서 확인 필요"));
        BODY.put("PUT /api/admin/users/{userId}/use", obj("use", true));
        BODY.put("PUT /api/admin/users/{userId}/role", obj("roleCd", "ADMIN"));
        BODY.put("PUT /api/logs/{serverId}/policy", obj("intervalMin", 5));
        Map<String, Object> logSrc = obj("name", "messages", "path", "/var/adm/messages", "tailLines", 200, "use", true);
        BODY.put("POST /api/logs/{serverId}/sources", logSrc);
        BODY.put("PUT /api/logs/{serverId}/sources/{logId}", logSrc);
        // 에이전트 API (text/plain)
        BODY.put("POST /agent-api/v1/report", "[sys]\nhostname=krcasmrx11\nos=SunOS 5.10\nagent_ver=1.9\n"
                + "[metric]\ncpu=12.5\nmem=40.2\ndisk=55\nsessions=120\n[proc]\n1|Y|24102|4.2|12.5\n"
                + "[service]\nSCB_fs_app1|RUNNING|5555|3.0|4.0\n");
        BODY.put("POST /agent-api/v1/daemon", "");
        BODY.put("POST /agent-api/v1/commands/{cmdId}/result", "RESULT: OK=1 FAIL=0\n");
        BODY.put("POST /agent-api/v1/logs/{logId}", "Oct 12 10:00:01 krcasmrx11 sshd[123]: Accepted publickey for root\n");
        BODY.put("POST /agent-api/v1/fallback", "[metric]\ncpu=12.5\nmem=40\ndisk=50\n[proc]\n1|Y|24102|1.5|2.5\n"
                + "[ps]\n24102|1|murex|6.4|12.8|01:02:03|/opt/murex/bin/mx_kernel -d\n");
    }

    /** API 1건의 예제 정보 */
    public static class Entry {
        public String tag;
        public String summary;
        public String method;
        public String path;               // 경로 템플릿 (/api/agents/{serverId}/restart)
        public String samplePath;         // 예시 값을 넣은 경로 (쿼리 포함)
        public Map<String, Object> pathVars = new LinkedHashMap<>();
        public Map<String, Object> query = new LinkedHashMap<>();
        public Object body;               // JSON(Map/List) / text(String) / null
        public boolean textBody;
        public boolean textResponse;
        public boolean agent;             // /agent-api : 토큰 헤더
        public boolean write;             // GET 아님 (CSRF 헤더 필요, 대부분 ADMIN)
        public String handler;            // 처리 소스 : 컨트롤러#메서드
        public String sourceFile;         // 처리 소스 파일 (프로젝트 기준 경로)
    }

    private final ApplicationContext ctx;
    private volatile List<Entry> entries;
    private final Map<java.lang.reflect.Method, Entry> byMethod = new HashMap<>();

    public ApiSampleCatalog(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    public List<Entry> entries() {
        if (entries == null) {
            synchronized (this) {
                if (entries == null) entries = build();
            }
        }
        return entries;
    }

    public Entry find(HandlerMethod hm) {
        entries();
        return byMethod.get(hm.getMethod());
    }

    private List<Entry> build() {
        List<Entry> out = new ArrayList<>();
        RequestMappingHandlerMapping mapping = ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        for (Map.Entry<RequestMappingInfo, HandlerMethod> me : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = me.getValue();
            Operation op = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), Operation.class);
            if (op == null) continue;
            for (String p : me.getKey().getPatternValues()) {
                if (!p.startsWith("/api/") && !p.startsWith("/agent-api/")) continue;
                for (RequestMethod rm : me.getKey().getMethodsCondition().getMethods()) {
                    Entry e = entry(hm, op, rm.name(), p);
                    out.add(e);
                    byMethod.putIfAbsent(hm.getMethod(), e);
                }
            }
        }
        out.sort(Comparator.comparing((Entry e) -> e.tag == null ? "~" : e.tag)
                .thenComparing(e -> e.path).thenComparing(e -> order(e.method)));
        return Collections.unmodifiableList(out);
    }

    private static int order(String m) {
        return Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH").indexOf(m);
    }

    private Entry entry(HandlerMethod hm, Operation op, String method, String path) {
        Entry e = new Entry();
        Tag tag = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), Tag.class);
        e.tag = tag == null ? "기타" : tag.name();
        e.summary = op.summary().isEmpty() ? hm.getMethod().getName() : op.summary();
        e.method = method;
        e.path = path;
        Class<?> bt = hm.getBeanType();
        e.handler = bt.getSimpleName() + "#" + hm.getMethod().getName();
        e.sourceFile = "src/main/java/" + bt.getName().replace('.', '/') + ".java";
        e.agent = path.startsWith("/agent-api/");
        e.write = !"GET".equals(method);
        String key = method + " " + path;

        // 경로 변수
        Matcher m = PATH_VAR.matcher(path);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = PATH_SAMPLE.getOrDefault(m.group(1), 1);
            e.pathVars.put(m.group(1), v);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);

        // 쿼리 : 필수(기본값 없음) + 예제로 보여 줄 것
        List<String> use = QUERY_USE.getOrDefault(key, Collections.<String>emptyList());
        Class<?> bodyType = null;
        boolean bodyBytes = false;
        for (MethodParameter mp : hm.getMethodParameters()) {
            RequestParam rp = mp.getParameterAnnotation(RequestParam.class);
            if (rp != null) {
                String name = !rp.value().isEmpty() ? rp.value() : rp.name();
                boolean required = rp.required() && ValueConstants.DEFAULT_NONE.equals(rp.defaultValue());
                if (required || use.contains(name)) {
                    Object v = QUERY_SAMPLE.containsKey(key + "#" + name) ? QUERY_SAMPLE.get(key + "#" + name)
                            : QUERY_SAMPLE.getOrDefault(name, "value");
                    e.query.put(name, v);
                }
            }
            if (mp.hasParameterAnnotation(RequestBody.class)) {
                bodyType = mp.getParameterType();
                bodyBytes = bodyType == byte[].class;
            }
        }
        if (key.equals("POST /agent-api/v1/daemon")) e.query.put("state", "STOPPED");
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Object> qe : e.query.entrySet()) {
            q.append(q.length() == 0 ? '?' : '&').append(qe.getKey()).append('=').append(qe.getValue());
        }
        e.samplePath = sb + q.toString();

        // 본문
        if (BODY.containsKey(key)) {
            e.body = BODY.get(key);
        } else if (bodyType != null && !bodyBytes && !Map.class.isAssignableFrom(bodyType)) {
            e.body = fromFields(bodyType);
        }
        e.textBody = e.body instanceof String || bodyBytes;
        if (bodyBytes && e.body == null) e.body = "";
        e.textResponse = e.agent || path.endsWith("/service-list") || producesText(hm);
        return e;
    }

    /** produces 가 text/* 인 API (예 : 예제 소스 내려받기) */
    private static boolean producesText(HandlerMethod hm) {
        org.springframework.web.bind.annotation.RequestMapping rm =
                AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), org.springframework.web.bind.annotation.RequestMapping.class);
        if (rm == null) return false;
        for (String p : rm.produces()) if (p.startsWith("text/")) return true;
        return false;
    }

    /** DTO 필드로 기본 예시 (표에 없을 때) */
    private static Map<String, Object> fromFields(Class<?> type) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            Object v = t == String.class ? "text" : (t == int.class || t == Integer.class || t == long.class || t == Long.class) ? 1
                    : (t == boolean.class || t == Boolean.class) ? Boolean.TRUE : List.class.isAssignableFrom(t) ? Collections.emptyList() : null;
            m.put(f.getName(), v);
        }
        return m;
    }

    // ================================================================ 예제 코드

    public String json(Object body) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body).replace("\r\n", "\n");
        } catch (JsonProcessingException ex) {
            return String.valueOf(body);
        }
    }

    private static String compactJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return String.valueOf(body);
        }
    }

    /** Swagger UI 설명에 붙일 Markdown (cURL / Java / Postman) */
    public String markdown(Entry e) {
        StringBuilder md = new StringBuilder();
        md.append("\n\n---\n#### 호출 예제 (복사해서 사용)\n");
        md.append(e.agent ? "에이전트 토큰 헤더 `X-SCB-Agent-Token` 필요 (응답 text/plain)\n"
                : e.write ? "로그인 세션 + CSRF 헤더 필요 · 변경·제어는 **ADMIN** 계정\n" : "로그인 세션 필요\n");
        if (!e.pathVars.isEmpty()) {
            md.append("\n경로의 ");
            boolean first = true;
            for (Map.Entry<String, Object> pv : e.pathVars.entrySet()) {
                md.append(first ? "" : ", ").append('`').append(pv.getKey()).append('=').append(pv.getValue()).append('`');
                first = false;
            }
            md.append(" 은 예시 값입니다. 실제 ID 로 바꾸세요. (서버 ID 는 GET /api/topology/servers 의 id)\n");
        }
        md.append("\n**cURL** (BASE·로그인은 맨 위 \"시작하기\" 참고)\n```bash\n").append(curl(e)).append("\n```\n");
        md.append("\n**Java** (`ScbApiClient api` - 맨 위 \"시작하기\" 참고)\n```java\n").append(java(e)).append("\n```\n");
        md.append("\n**Postman** : 컬렉션 [").append(e.tag).append("] › [").append(e.summary).append("]");
        if (e.body != null && !e.textBody) md.append(" · Body raw JSON");
        md.append('\n');
        return md.toString();
    }

    public String curl(Entry e) {
        StringBuilder c = new StringBuilder("curl -s");
        if (e.agent) {
            c.append(" -H \"X-SCB-Agent-Token: $AGENT_TOKEN\"");
        } else {
            c.append(" -b cj.txt -c cj.txt");
            if (e.write) c.append(" -H \"X-XSRF-TOKEN: $XSRF\"");
        }
        if (!"GET".equals(e.method)) c.append(" -X ").append(e.method);
        if (e.body != null) {
            if (e.textBody) {
                c.append(" -H \"Content-Type: text/plain; charset=UTF-8\" --data-binary ").append(shellQuote(String.valueOf(e.body)));
            } else {
                c.append(" -H \"Content-Type: application/json\" -d ").append(shellQuote(compactJson(e.body)));
            }
        }
        c.append(" \"$BASE").append(e.samplePath).append('"');
        return c.toString();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public String java(Entry e) {
        StringBuilder j = new StringBuilder();
        String p = javaString(e.samplePath);
        if (e.agent) {
            j.append("String token = System.getenv(\"SCB_AGENT_TOKEN\");\n");
            if ("GET".equals(e.method)) {
                j.append("String text = api.agentGet(").append(p).append(", token);\n");
            } else {
                j.append("String text = api.agentPost(").append(p).append(", ").append(javaString(String.valueOf(e.body == null ? "" : e.body)))
                        .append(", token);\n");
            }
            j.append("System.out.println(text);");
            return j.toString();
        }
        if (e.textResponse) {
            j.append("String text = api.getText(").append(p).append(");\nSystem.out.println(text);");
            return j.toString();
        }
        String call;
        switch (e.method) {
            case "POST": call = "api.post(" + p + ", " + (e.body == null ? "null" : javaValue(e.body, 1)) + ")"; break;
            case "PUT": call = "api.put(" + p + ", " + (e.body == null ? "null" : javaValue(e.body, 1)) + ")"; break;
            case "DELETE": call = "api.delete(" + p + ")"; break;
            default: call = "api.get(" + p + ")";
        }
        j.append("Object result = ").append(call).append(";\n");
        j.append("System.out.println(ScbApiClient.Json.pretty(result));");
        return j.toString();
    }

    /** 예시 값 → Java 코드 (ScbApiClient.map / Arrays.asList) */
    private static String javaValue(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof String) return javaString((String) v);
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        String ind = new String(new char[depth * 4]).replace('\0', ' ');
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("ScbApiClient.map(");
            boolean first = true;
            for (Map.Entry<?, ?> me : ((Map<?, ?>) v).entrySet()) {
                sb.append(first ? "\n" : ",\n").append(ind).append("    ")
                  .append(javaString(String.valueOf(me.getKey()))).append(", ").append(javaValue(me.getValue(), depth + 1));
                first = false;
            }
            return sb.append(")").toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("java.util.Arrays.asList(");
            boolean first = true;
            for (Object o : (List<?>) v) {
                sb.append(first ? "" : ", ").append(javaValue(o, depth + 1));
                first = false;
            }
            return sb.append(")").toString();
        }
        return javaString(String.valueOf(v));
    }

    private static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ================================================================ Postman 컬렉션 (v2.1)

    /**
     * Postman 컬렉션 : Postman [Import] 로 가져오면 태그별 폴더 + 각 API 요청 + 예시 본문.
     * 변수 baseUrl / username / password / agentToken, 로그인 요청, CSRF 자동 처리(컬렉션 스크립트).
     */
    public Map<String, Object> postmanCollection(String baseUrl) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("info", obj("name", "SCB 통합 서버 모니터링 API",
                "description", "1) 컬렉션 변수 baseUrl / username / password 지정 (Variables 탭)\n"
                        + "2) [0. 로그인] 폴더의 요청 두 개를 차례로 실행 (CSRF 토큰은 컬렉션 스크립트가 자동으로 헤더에 넣음)\n"
                        + "3) 원하는 API 실행 - 변경·제어 API 는 ADMIN 계정\n"
                        + "에이전트 API(/agent-api) 는 변수 agentToken (네트워크 구성도에서 발급한 토큰)\n"
                        + "Swagger UI : " + baseUrl + "/swagger-ui.html",
                // 형식 식별자 (Postman 이 컬렉션 버전을 구분하는 고정 문자열, 접속하지 않음)
                "schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"));
        col.put("variable", list(
                obj("key", "baseUrl", "value", baseUrl),
                obj("key", "username", "value", "admin"),
                obj("key", "password", "value", ""),
                obj("key", "agentToken", "value", ""),
                obj("key", "xsrf", "value", "")));
        col.put("event", list(
                obj("listen", "prerequest", "script", obj("type", "text/javascript", "exec", list(
                        "// 변경 요청(POST/PUT/DELETE)에 CSRF 헤더 자동 추가",
                        "if (pm.request.method !== 'GET' && pm.collectionVariables.get('xsrf')) {",
                        "    pm.request.headers.upsert({ key: 'X-XSRF-TOKEN', value: pm.collectionVariables.get('xsrf') });",
                        "}"))),
                obj("listen", "test", "script", obj("type", "text/javascript", "exec", list(
                        "// 응답 쿠키의 XSRF-TOKEN 을 변수에 보관 (로그인 뒤 토큰이 바뀜)",
                        "const t = pm.cookies.get('XSRF-TOKEN');",
                        "if (t) { pm.collectionVariables.set('xsrf', t); }")))));

        List<Object> folders = new ArrayList<>();
        folders.add(obj("name", "0. 로그인", "item", list(
                obj("name", "1) 로그인 화면 (세션·CSRF 쿠키 받기)", "request", obj(
                        "method", "GET", "url", url("/login", Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap()))),
                obj("name", "2) 로그인", "request", obj(
                        "method", "POST",
                        "header", list(obj("key", "Content-Type", "value", "application/x-www-form-urlencoded")),
                        "body", obj("mode", "urlencoded", "urlencoded", list(
                                obj("key", "username", "value", "{{username}}"),
                                obj("key", "password", "value", "{{password}}"),
                                obj("key", "_csrf", "value", "{{xsrf}}"))),
                        "url", url("/login", Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap()),
                        "description", "성공하면 메인 화면으로 이동(302 → 200). 실패하면 /login?error=...")),
                obj("name", "3) 로그아웃", "request", obj("method", "POST", "url",
                        url("/logout", Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap()))))));

        Map<String, List<Object>> byTag = new LinkedHashMap<>();
        for (Entry e : entries()) {
            byTag.computeIfAbsent(e.tag, k -> new ArrayList<>()).add(postmanItem(e));
        }
        byTag.forEach((tag, items) -> folders.add(obj("name", tag, "item", items)));
        col.put("item", folders);
        return col;
    }

    /** Postman 요청 1건 (컬렉션 item) */
    public Map<String, Object> postmanItem(Entry e) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", e.method);
        List<Object> headers = new ArrayList<>();
        if (e.agent) headers.add(obj("key", "X-SCB-Agent-Token", "value", "{{agentToken}}"));
        if (e.body != null) {
            headers.add(obj("key", "Content-Type", "value", e.textBody ? "text/plain; charset=UTF-8" : "application/json"));
            req.put("body", e.textBody ? obj("mode", "raw", "raw", String.valueOf(e.body))
                    : obj("mode", "raw", "raw", json(e.body), "options", obj("raw", obj("language", "json"))));
        }
        if (!headers.isEmpty()) req.put("header", headers);
        req.put("url", url(e.path, e.pathVars, e.query));
        req.put("description", e.method + " " + e.path + (e.agent ? "  (에이전트 토큰)" : e.write ? "  (ADMIN)" : ""));
        return obj("name", e.summary, "request", req);
    }

    /** Postman [Import] → [Raw text] 에 붙여넣는 cURL (컬렉션 변수 {{baseUrl}} / {{xsrf}} / {{agentToken}}) */
    public String postmanCurl(Entry e) {
        StringBuilder c = new StringBuilder("curl -X ").append(e.method).append(" \"{{baseUrl}}").append(e.samplePath).append('"');
        if (e.agent) c.append(" \\\n  -H \"X-SCB-Agent-Token: {{agentToken}}\"");
        else if (e.write) c.append(" \\\n  -H \"X-XSRF-TOKEN: {{xsrf}}\"");
        if (e.body != null) {
            c.append(" \\\n  -H \"Content-Type: ").append(e.textBody ? "text/plain; charset=UTF-8" : "application/json").append('"');
            c.append(" \\\n  --data-raw ").append(shellQuote(e.textBody ? String.valueOf(e.body) : json(e.body)));
        }
        return c.toString();
    }

    // ================================================================ JavaScript / Python 예제

    /** JavaScript (fetch) : 이 웹에 로그인한 브라우저에서 실행 (BASE / xsrf() / AGENT_TOKEN 은 "시작하기") */
    public String javascript(Entry e) {
        List<String> h = new ArrayList<>();
        if (e.agent) h.add("'X-SCB-Agent-Token': AGENT_TOKEN");
        else if (e.write) h.add("'X-XSRF-TOKEN': xsrf()");
        if (e.body != null) h.add("'Content-Type': '" + (e.textBody ? "text/plain; charset=UTF-8" : "application/json") + "'");
        StringBuilder j = new StringBuilder("const res = await fetch(BASE + ").append(compactJson(e.samplePath)).append(", {\n");
        j.append("    method: '").append(e.method).append("'");
        if (!e.agent) j.append(",\n    credentials: 'include'");
        if (!h.isEmpty()) j.append(",\n    headers: { ").append(String.join(", ", h)).append(" }");
        if (e.body != null) {
            j.append(",\n    body: ").append(e.textBody ? compactJson(String.valueOf(e.body))
                    : "JSON.stringify(" + json(e.body).replace("\n", "\n    ") + ")");
        }
        j.append("\n});\n");
        if (e.textResponse) j.append("console.log(res.status, await res.text());");
        else if (e.write) j.append("console.log(res.status, await res.text());");
        else j.append("console.log(res.status, await res.json());");
        return j.toString();
    }

    /** Python (표준 라이브러리 urllib, 추가 설치 없음) : call() / AGENT_TOKEN 은 "시작하기" */
    public String python(Entry e) {
        List<String> args = new ArrayList<>();
        args.add(compactJson(e.method));
        args.add(compactJson(e.samplePath));
        if (e.body != null) {
            args.add(e.textBody ? "text=" + compactJson(String.valueOf(e.body)) : "json_body=" + pyValue(e.body, 1));
        }
        if (e.agent) args.add("headers={\"X-SCB-Agent-Token\": AGENT_TOKEN}");
        StringBuilder p = new StringBuilder("status, body = call(").append(String.join(",\n    ", args)).append(")\n");
        if (e.textResponse || e.write) p.append("print(status, body)");
        else p.append("print(status)\nprint(json.dumps(json.loads(body), ensure_ascii=False, indent=2))");
        return p.toString();
    }

    private static String pyValue(Object v, int depth) {
        if (v == null) return "None";
        if (v instanceof Boolean) return ((Boolean) v) ? "True" : "False";
        if (v instanceof Number) return String.valueOf(v);
        if (v instanceof String) return compactJson(v);
        String ind = new String(new char[depth * 4]).replace('\0', ' ');
        if (v instanceof Map) {
            if (((Map<?, ?>) v).isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> me : ((Map<?, ?>) v).entrySet()) {
                sb.append(first ? "\n" : ",\n").append(ind).append("    ")
                  .append(compactJson(String.valueOf(me.getKey()))).append(": ").append(pyValue(me.getValue(), depth + 1));
                first = false;
            }
            return sb.append('\n').append(ind).append('}').toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                sb.append(first ? "" : ", ").append(pyValue(o, depth + 1));
                first = false;
            }
            return sb.append(']').toString();
        }
        return compactJson(String.valueOf(v));
    }

    // ================================================================ API 가이드 화면 (/admin/api-guide)

    /** 시작하기 : 언어별 로그인·준비 코드 (각 API 예제 앞에 한 번) */
    public Map<String, Object> guide(String baseUrl, String contextPath) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("curl", "BASE=" + baseUrl + "\n"
                + "curl -s -c cj.txt -b cj.txt \"$BASE/login\" -o /dev/null\n"
                + "XSRF=$(awk '$6==\"XSRF-TOKEN\"{print $7}' cj.txt)\n"
                + "curl -s -c cj.txt -b cj.txt --data-urlencode username=admin --data-urlencode \"password=$SCB_API_PASSWORD\" \\\n"
                + "     --data-urlencode \"_csrf=$XSRF\" \"$BASE/login\" -o /dev/null\n"
                + "XSRF=$(awk '$6==\"XSRF-TOKEN\"{print $7}' cj.txt)   # 로그인하면 CSRF 토큰이 바뀜\n"
                + "AGENT_TOKEN=...                                       # 에이전트 API 만 (네트워크 구성도에서 발급한 토큰)");
        g.put("java", "// ScbApiClient.java (이 화면 [소스] 탭) 를 프로젝트에 복사 - JDK 1.8+, 외부 라이브러리 없음\n"
                + "ScbApiClient api = new ScbApiClient(\"" + baseUrl + "\");\n"
                + "api.login(\"admin\", System.getenv(\"SCB_API_PASSWORD\"));   // 비밀번호는 코드에 쓰지 말 것");
        g.put("javascript", "// 이 웹에 로그인한 브라우저의 개발자 도구(F12) > Console 에서 실행 (같은 주소라 세션 쿠키 사용)\n"
                + "const BASE = location.origin + " + compactJson(contextPath == null ? "" : contextPath) + ";\n"
                + "const xsrf = () => decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '');\n"
                + "const AGENT_TOKEN = '...';   // 에이전트 API 만");
        g.put("python", "# Python 3 표준 라이브러리만 사용 (pip 설치 없음, 오프라인 가능)\n"
                + "import os, json, urllib.request, urllib.parse, urllib.error, http.cookiejar\n"
                + "BASE = " + compactJson(baseUrl) + "\n"
                + "jar = http.cookiejar.CookieJar()\n"
                + "web = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))\n\n"
                + "def xsrf():\n"
                + "    return next((c.value for c in jar if c.name == \"XSRF-TOKEN\"), \"\")\n\n"
                + "def call(method, path, json_body=None, text=None, headers=None):\n"
                + "    \"\"\"(상태 코드, 응답 문자열) - 로그인 세션 쿠키·CSRF 헤더 자동\"\"\"\n"
                + "    h = dict(headers or {})\n"
                + "    data = None\n"
                + "    if json_body is not None:\n"
                + "        data = json.dumps(json_body).encode(\"utf-8\")\n"
                + "        h[\"Content-Type\"] = \"application/json\"\n"
                + "    elif text is not None:\n"
                + "        data = text.encode(\"utf-8\")\n"
                + "        h[\"Content-Type\"] = \"text/plain; charset=UTF-8\"\n"
                + "    if method != \"GET\" and \"X-SCB-Agent-Token\" not in h:\n"
                + "        h[\"X-XSRF-TOKEN\"] = xsrf()\n"
                + "    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)\n"
                + "    try:\n"
                + "        with web.open(req, timeout=30) as r:\n"
                + "            return r.status, r.read().decode(\"utf-8\")\n"
                + "    except urllib.error.HTTPError as ex:\n"
                + "        return ex.code, ex.read().decode(\"utf-8\", \"replace\")\n\n"
                + "web.open(BASE + \"/login\").read()                          # 세션·CSRF 쿠키 받기\n"
                + "web.open(BASE + \"/login\", urllib.parse.urlencode({\"username\": \"admin\",\n"
                + "         \"password\": os.environ[\"SCB_API_PASSWORD\"], \"_csrf\": xsrf()}).encode()).read()\n"
                + "AGENT_TOKEN = os.environ.get(\"SCB_AGENT_TOKEN\", \"\")      # 에이전트 API 만");
        g.put("postman", "1) [Postman 컬렉션 내려받기] → Postman [Import]\n"
                + "2) 컬렉션 Variables : baseUrl = " + baseUrl + " / username / password (에이전트 API 는 agentToken)\n"
                + "3) [0. 로그인] 폴더 1) → 2) 실행 (CSRF 헤더는 컬렉션 스크립트가 자동 처리)\n"
                + "4) 원하는 API 실행 - 또는 각 API 의 \"Postman 가져오기용 cURL\" 을 [Import] > [Raw text] 에 붙여넣기");
        return g;
    }

    /** 화면용 API 1건 : 기본 정보 + 언어별 예제 + Postman */
    public Map<String, Object> view(Entry e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.method + " " + e.path);
        v.put("tag", e.tag);
        v.put("summary", e.summary);
        v.put("method", e.method);
        v.put("path", e.path);
        v.put("samplePath", e.samplePath);
        v.put("pathVars", e.pathVars);
        v.put("query", e.query);
        v.put("body", e.body == null ? null : e.textBody ? String.valueOf(e.body) : json(e.body));
        v.put("textBody", e.textBody);
        v.put("textResponse", e.textResponse);
        v.put("agent", e.agent);
        v.put("write", e.write);
        v.put("auth", e.agent ? "에이전트 토큰 (X-SCB-Agent-Token)" : e.write ? "로그인 + CSRF 헤더 · ADMIN" : "로그인 세션");
        v.put("handler", e.handler);
        v.put("sourceFile", e.sourceFile);
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("curl", curl(e));
        code.put("java", java(e));
        code.put("javascript", javascript(e));
        code.put("python", python(e));
        code.put("postman", postmanCurl(e));
        v.put("code", code);
        v.put("postmanItem", json(postmanItem(e)));
        return v;
    }

    /**
     * 전체 API 예제를 한 파일로 : ScbApiExamples.java (ScbApiClient.java 와 같은 폴더에 두고 컴파일)
     * main 은 조회(GET) 웹 API 만 실행, 변경·제어·에이전트 API 는 메서드로만 제공 (실제 데이터가 바뀜)
     */
    public String examplesJava(String baseUrl) {
        StringBuilder j = new StringBuilder();
        j.append("import java.util.Arrays;\n\n");
        j.append("/**\n * SCB 통합 서버 모니터링 API 전체 예제 (자동 생성 - API 가이드 화면).\n");
        j.append(" * 준비 : ScbApiClient.java 와 같은 폴더, JDK 1.8+\n");
        j.append(" *   javac -encoding UTF-8 ScbApiClient.java ScbApiExamples.java\n");
        j.append(" *   set SCB_API_PASSWORD=비밀번호   (Solaris/Linux : export SCB_API_PASSWORD=...)\n");
        j.append(" *   java ScbApiExamples [").append(baseUrl).append("]\n");
        j.append(" * main 은 경로 변수가 없는 조회(GET) API 만 실행합니다. 경로 변수 API 는 실제 ID 로, 변경·제어 API 는 메서드를 직접 호출하세요 (ADMIN, 실제 데이터 변경).\n */\n");
        j.append("public class ScbApiExamples {\n\n");
        j.append("    public static void main(String[] args) throws Exception {\n");
        j.append("        ScbApiClient api = new ScbApiClient(args.length > 0 ? args[0] : ").append(javaString(baseUrl)).append(");\n");
        j.append("        api.login(\"admin\", System.getenv(\"SCB_API_PASSWORD\"));\n");
        List<Entry> all = entries();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) names.add(methodName(i, all.get(i)));
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (e.agent || e.write || !e.pathVars.isEmpty() || e.query.containsValue("value")) continue;   // 경로 변수 API 는 실제 ID 로 직접 호출
            j.append("        run(\"").append(e.method).append(' ').append(e.path).append("\", () -> ").append(names.get(i)).append("(api));\n");
        }
        j.append("        api.logout();\n    }\n\n");
        j.append("    interface Call { void run() throws Exception; }\n\n");
        j.append("    static void run(String name, Call c) {\n");
        j.append("        System.out.println(\"===== \" + name);\n");
        j.append("        try { c.run(); } catch (Exception ex) { System.out.println(\"실패 : \" + ex.getMessage()); }\n    }\n");
        String lastTag = null;
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (!e.tag.equals(lastTag)) {
                j.append("\n    // ==================== ").append(e.tag).append('\n');
                lastTag = e.tag;
            }
            j.append("\n    /** ").append(e.summary.replace("*/", "*\\/")).append(" - ").append(e.method).append(' ').append(e.path)
             .append(e.agent ? " (에이전트 토큰)" : e.write ? " (ADMIN, 데이터 변경)" : "").append(" */\n");
            j.append("    static void ").append(names.get(i)).append("(ScbApiClient api) throws Exception {\n");
            for (String line : java(e).split("\n")) j.append("        ").append(line).append('\n');
            j.append("    }\n");
        }
        j.append("}\n");
        return j.toString();
    }

    private static String methodName(int i, Entry e) {
        StringBuilder n = new StringBuilder(e.method.toLowerCase());
        for (String seg : e.path.replaceAll("^/(agent-)?api/(v1/)?", "").split("[/{}\\-_.]+")) {
            if (seg.isEmpty()) continue;
            n.append(Character.toUpperCase(seg.charAt(0))).append(seg.substring(1));
        }
        return String.format("ex%02d_%s", i + 1, n.toString().replaceAll("[^A-Za-z0-9_]", ""));
    }

    /** Postman url : {{baseUrl}}/api/agents/:serverId/restart + 경로 변수 + 쿼리 */
    private static Map<String, Object> url(String path, Map<String, Object> vars, Map<String, Object> query) {
        String p = PATH_VAR.matcher(path).replaceAll(":$1");
        List<Object> segs = new ArrayList<>();
        for (String s : p.split("/")) if (!s.isEmpty()) segs.add(s);
        StringBuilder raw = new StringBuilder("{{baseUrl}}").append(p);
        List<Object> q = new ArrayList<>();
        for (Map.Entry<String, Object> qe : query.entrySet()) {
            raw.append(q.isEmpty() ? '?' : '&').append(qe.getKey()).append('=').append(qe.getValue());
            q.add(obj("key", qe.getKey(), "value", String.valueOf(qe.getValue())));
        }
        Map<String, Object> u = obj("raw", raw.toString(), "host", list("{{baseUrl}}"), "path", segs);
        if (!q.isEmpty()) u.put("query", q);
        if (!vars.isEmpty()) {
            List<Object> v = new ArrayList<>();
            vars.forEach((k, val) -> v.add(obj("key", k, "value", String.valueOf(val))));
            u.put("variable", v);
        }
        return u;
    }

    // ================================================================ 내부

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }
}
