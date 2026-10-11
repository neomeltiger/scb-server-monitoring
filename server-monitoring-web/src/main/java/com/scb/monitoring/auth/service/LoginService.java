package com.scb.monitoring.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.mapper.UserMapper;
import com.scb.monitoring.config.AppSecurityProperties;

/**
 * 로그인 사용자 조회 (Spring Security UserDetailsService) 와 로그인 성공/실패 기록.
 */
@Service
public class LoginService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_LOCKED = "LOCKED";
    public static final String RESULT_DISABLED = "DISABLED";
    public static final String RESULT_PENDING = "PENDING";
    public static final String RESULT_REJECTED = "REJECTED";
    public static final String RESULT_PW_CHANGE = "PW_CHANGE";

    private final UserMapper mapper;
    private final AppSecurityProperties props;
    private final PasswordEncoder passwordEncoder;

    public LoginService(UserMapper mapper, AppSecurityProperties props, PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.props = props;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUser loadUserByUsername(String username) {
        AppUser user = mapper.selectUser(username);
        if (user == null) throw new UsernameNotFoundException("user not found");
        return user;
    }

    @Transactional
    public void loginSucceeded(String userId, String clientIp) {
        mapper.updateLoginSuccess(userId, clientIp);
        mapper.insertLoginHist(userId, clientIp, RESULT_SUCCESS, null);
        log.info("LOGIN SUCCESS {} from {}", userId, clientIp);
    }

    /**
     * 비밀번호 오류 : 실패 횟수 +1 (설정 횟수 이상이면 잠금). 없는 ID 도 이력은 남깁니다.
     * @return 이번 실패로 계정이 잠겼으면 true
     */
    @Transactional
    public boolean passwordFailed(String userId, String clientIp) {
        boolean lockedNow = false;
        AppUser before = mapper.selectUser(userId);
        if (before != null) {
            mapper.increaseFailCount(userId, props.getMaxLoginFailures());
            AppUser after = mapper.selectUser(userId);
            lockedNow = after != null && !after.isAccountNonLocked() && before.isAccountNonLocked();
        }
        String msg = before == null ? "unknown user" : "fail " + (before.getFailCnt() + 1) + "/" + props.getMaxLoginFailures();
        mapper.insertLoginHist(userId, clientIp, lockedNow ? RESULT_LOCKED : RESULT_FAIL, msg);
        log.warn("LOGIN FAIL {} from {} ({}){}", userId, clientIp, msg, lockedNow ? " -> LOCKED" : "");
        return lockedNow;
    }

    /**
     * 비밀번호는 맞았지만 로그인할 수 없는 사유 (로그인 화면 메시지용)
     * @return pending(승인 대기) | rejected(반려) | disabled(사용 중지)
     */
    @Transactional(readOnly = true)
    public String disabledReason(String userId) {
        AppUser u = mapper.selectUser(userId);
        if (u != null && AppUser.APPR_PENDING.equals(u.getApprStatus())) return "pending";
        if (u != null && AppUser.APPR_REJECTED.equals(u.getApprStatus())) return "rejected";
        return "disabled";
    }

    @Transactional
    public void loginRejected(String userId, String clientIp, String resultCd) {
        mapper.insertLoginHist(userId, clientIp, resultCd, null);
        log.warn("LOGIN {} {} from {}", resultCd, userId, clientIp);
    }

    /**
     * 비밀번호 변경. 현재 비밀번호가 틀리면 로그인 실패와 똑같이 실패 횟수를 올립니다(잠금 포함).
     * 계정 상태(잠금/중지/승인 대기)는 현재 비밀번호가 맞은 뒤에만 알려줍니다.
     * @return null 이면 성공, 아니면 화면에 보여줄 오류 메시지
     */
    @Transactional
    public String changePassword(String userId, String currentPassword, String newPassword, String clientIp) {
        AppUser u = mapper.selectUser(userId);
        if (u == null || !passwordEncoder.matches(currentPassword, u.getPasswordHash())) {
            boolean lockedNow = passwordFailed(userId, clientIp);
            return lockedNow ? "현재 비밀번호 오류가 반복되어 계정이 잠겼습니다. 관리자에게 잠금 해제를 요청하세요."
                    : "아이디 또는 현재 비밀번호가 올바르지 않습니다. (연속 " + props.getMaxLoginFailures() + "회 실패 시 계정이 잠깁니다)";
        }
        if (!u.isAccountNonLocked()) return "잠긴 계정입니다. 관리자에게 잠금 해제를 요청하세요.";
        if (!u.isEnabled()) {
            String r = disabledReason(userId);
            return "pending".equals(r) ? "가입 승인 대기 중인 계정입니다. 승인 후 변경할 수 있습니다."
                    : "rejected".equals(r) ? "가입이 반려된 계정입니다." : "사용이 중지된 계정입니다. 관리자에게 문의하세요.";
        }
        if (passwordEncoder.matches(newPassword, u.getPasswordHash())) return "새 비밀번호가 현재 비밀번호와 같습니다.";
        mapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        mapper.insertLoginHist(userId, clientIp, RESULT_PW_CHANGE, null);
        log.info("PASSWORD CHANGED {} from {}", userId, clientIp);
        return null;
    }
}
