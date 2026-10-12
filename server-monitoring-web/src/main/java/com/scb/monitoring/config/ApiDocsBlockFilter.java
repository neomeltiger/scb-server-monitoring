package com.scb.monitoring.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 운영 환경 API 문서 제외 : app.api-docs.enabled=false (프로파일 prod 또는 API_DOCS_ENABLED=false) 일 때만 등록.
 * Swagger UI · OpenAPI 문서 · API 가이드 화면 · 예제 소스 · Postman 컬렉션 주소를 로그인 전에 404 로 끝냄
 * (주소가 있다는 것도 드러내지 않도록 403 이 아닌 404, Spring Security 보다 먼저 실행).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "app.api-docs", name = "enabled", havingValue = "false")
public class ApiDocsBlockFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiDocsBlockFilter.class);

    /** 막는 주소 (컨텍스트 경로 뒤) : 끝이 / 면 그 아래 전체 */
    static final List<String> BLOCKED = Arrays.asList(
            "/swagger-ui.html", "/swagger-ui/", "/v3/api-docs", "/v3/api-docs/", "/webjars/swagger-ui/",
            "/admin/api-guide", "/samples/", "/api/docs/", "/api/admin/docs/", "/js/api_guide.js");

    public ApiDocsBlockFilter() {
        log.info("API 문서 제외 (운영) : Swagger UI · API 문서 · API 가이드 · 예제 소스 주소는 404");
    }

    static boolean blocked(String path) {
        if (path == null) return false;
        for (String b : BLOCKED) {
            if (b.endsWith("/") ? path.startsWith(b) || path.equals(b.substring(0, b.length() - 1)) : path.equals(b) || path.startsWith(b + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        int semi = path.indexOf(';');
        if (semi >= 0) path = path.substring(0, semi);         // ;jsessionid 등
        if (blocked(path.replaceAll("/{2,}", "/"))) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(req, res);
    }
}
