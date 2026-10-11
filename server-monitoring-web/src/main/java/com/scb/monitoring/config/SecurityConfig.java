package com.scb.monitoring.config;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import com.scb.monitoring.auth.service.LoginService;

/**
 * 로그인 / 접근 제어.
 * <ul>
 *   <li>사용자 : DB tb_user (auth.service.LoginService), 비밀번호 BCrypt</li>
 *   <li>연속 실패 app.security.max-login-failures 회(기본 5) 이상이면 계정 잠금, 로그인 이력 tb_login_hist</li>
 *   <li>가입 신청(/signup)·비밀번호 변경(/password)은 로그인 없이, 승인(PENDING→APPROVED)된 사용자만 로그인</li>
 *   <li>조회(GET)는 로그인 사용자, 변경/삭제/제어(/api/** 의 POST·PUT·DELETE)는 ADMIN 만</li>
 *   <li>사용자 관리(/admin/**, /api/admin/**)와 웹 콘솔(/console, /ssh)은 ADMIN 만</li>
 *   <li>계정 상태(잠금/중지/승인 대기/반려)는 비밀번호가 맞은 뒤에만 알려줌 (ID 존재·상태 노출 방지)</li>
 *   <li>/agent-api/** 는 서버 에이전트 전용 (세션 대신 토큰 인증 - agent.controller.AgentApiController)</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    /** 로그인 성공 시각 (상단 사용자 정보에 표시) */
    public static final String SESSION_LOGIN_TIME = "loginTime";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginService loginService) throws Exception {
        http
            .authorizeRequests(auth -> auth
                .antMatchers("/css/**", "/js/**", "/images/**", "/vendor/**", "/manual/**", "/favicon.ico", "/login", "/signup", "/password", "/error").permitAll()
                .antMatchers("/agent-api/**").permitAll()
                .antMatchers("/admin/**", "/api/admin/**", "/console", "/ssh").hasRole("ADMIN")
                .antMatchers(HttpMethod.GET, "/api/**").authenticated()
                .antMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.ignoringAntMatchers("/agent-api/**"))
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(new LoginSuccessHandler(loginService))
                .failureHandler(new LoginFailureHandler(loginService))
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            .exceptionHandling(ex -> ex.accessDeniedHandler(SecurityConfig::accessDenied))
            .headers(headers -> headers.frameOptions().sameOrigin());
        return http.build();
    }

    /**
     * DB 사용자 인증. 기본 동작은 비밀번호 확인 전에 잠금/중지를 검사하므로
     * 비밀번호를 몰라도 계정 상태를 알 수 있음 → 비밀번호 확인 뒤에 검사하도록 바꿈.
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(LoginService loginService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(loginService);
        p.setPasswordEncoder(passwordEncoder);
        p.setPreAuthenticationChecks(user -> { });
        p.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return p;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 권한 없음 : API / 웹소켓은 403, 화면(GET)은 메인 홈으로 보내고 안내 (/?denied)
     */
    static void accessDenied(HttpServletRequest request, HttpServletResponse response,
                             org.springframework.security.access.AccessDeniedException e) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("GET".equals(request.getMethod()) && !path.startsWith("/api/") && !path.equals("/ssh")) {
            response.sendRedirect(request.getContextPath() + "/?denied");
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    /** 로그인 성공 : 접속 시각/이력 기록 후 원래 가려던 화면(없으면 메인)으로 */
    static class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
        private final LoginService loginService;

        LoginSuccessHandler(LoginService loginService) {
            this.loginService = loginService;
            setDefaultTargetUrl("/");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws ServletException, IOException {
            request.getSession().setAttribute(SESSION_LOGIN_TIME,
                    new SimpleDateFormat("yyyy. M. d. HH:mm:ss").format(new Date()));
            loginService.loginSucceeded(authentication.getName(), request.getRemoteAddr());
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /** 로그인 실패 : 사유별 메시지(/login?error=bad|locked|disabled|pending|rejected), 실패 횟수·이력 기록 */
    static class LoginFailureHandler implements AuthenticationFailureHandler {
        private final LoginService loginService;

        LoginFailureHandler(LoginService loginService) {
            this.loginService = loginService;
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException {
            String userId = request.getParameter("username");
            userId = userId == null ? "" : userId.trim();
            if (userId.length() > 30) userId = userId.substring(0, 30);
            String ip = request.getRemoteAddr();

            String reason;
            if (exception instanceof LockedException) {
                loginService.loginRejected(userId, ip, LoginService.RESULT_LOCKED);
                reason = "locked";
            } else if (exception instanceof DisabledException) {
                reason = loginService.disabledReason(userId);
                loginService.loginRejected(userId, ip, "pending".equals(reason) ? LoginService.RESULT_PENDING
                        : "rejected".equals(reason) ? LoginService.RESULT_REJECTED : LoginService.RESULT_DISABLED);
            } else {
                reason = loginService.passwordFailed(userId, ip) ? "locked" : "bad";
            }
            response.sendRedirect(request.getContextPath() + "/login?error=" + reason);
        }
    }
}
