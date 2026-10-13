package com.scb.monitoring.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 임시 비밀번호로 로그인한 사용자 : 비밀번호를 바꾸기 전에는 비밀번호 변경 화면만 (다른 화면 → /password?temp, API → 403).
 * 로그인 성공 처리(SecurityConfig.LoginSuccessHandler)가 세션에 표시하고, 비밀번호를 바꾸면 세션을 끝내므로 함께 사라짐.
 */
public class PasswordChangeInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "SCB_PW_CHANGE_REQUIRED";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        HttpSession s = req.getSession(false);
        if (s == null || !Boolean.TRUE.equals(s.getAttribute(SESSION_KEY))) return true;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.startsWith("/api/") || path.startsWith("/agent-api/")) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"임시 비밀번호를 먼저 바꾸세요. (비밀번호 변경 화면)\"}");
            return false;
        }
        res.sendRedirect(req.getContextPath() + "/password?temp");
        return false;
    }
}
