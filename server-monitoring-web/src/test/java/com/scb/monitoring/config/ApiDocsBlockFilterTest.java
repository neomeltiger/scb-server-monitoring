package com.scb.monitoring.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 운영(prod) API 문서 제외 : Swagger · API 가이드 · 예제 소스 주소는 404, 나머지는 그대로 */
class ApiDocsBlockFilterTest {

    private final ApiDocsBlockFilter filter = new ApiDocsBlockFilter();

    @Test
    void blocksApiDocsPaths() {
        for (String p : new String[] {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/1-web-api",
                "/admin/api-guide", "/samples/ScbApiClient.java", "/api/docs/postman-collection",
                "/api/admin/docs/catalog", "/api/admin/docs/examples-java", "/js/api_guide.js"}) {
            assertTrue(ApiDocsBlockFilter.blocked(p), p);
        }
    }

    @Test
    void keepsOtherPaths() {
        for (String p : new String[] {"/", "/network", "/admin/users", "/api/agents", "/api/admin/users", "/agent-api/v1/heartbeat",
                "/js/script.js", "/manual/index.html", "/admin/api-guides-x", "/api/documents"}) {
            assertFalse(ApiDocsBlockFilter.blocked(p), p);
        }
    }

    @Test
    void returns404WithContextPath() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/server-monitoring-web/swagger-ui.html");
        req.setContextPath("/server-monitoring-web");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertEquals(404, res.getStatus());
        assertNull(chain.getRequest());

        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/server-monitoring-web/network");
        ok.setContextPath("/server-monitoring-web");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(ok, new MockHttpServletResponse(), chain2);
        assertNotNull(chain2.getRequest());
    }

    @Test
    void ignoresSessionIdAndDoubleSlash() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "//admin//api-guide;jsessionid=ABC");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(404, res.getStatus());
    }
}
