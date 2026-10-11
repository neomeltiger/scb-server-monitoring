package com.scb.monitoring.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.dto.SignupForm;
import com.scb.monitoring.auth.service.LoginService;
import com.scb.monitoring.auth.service.UserAdminService;
import com.scb.monitoring.topology.service.TopologyException;
import com.scb.monitoring.config.AppSecurityProperties;

/** 로그인 사용자 (tb_user) : 조회, 실패 횟수/잠금, 성공 시 초기화, 로그인 이력 */
@MybatisTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-ansi.sql",
        "spring.sql.init.data-locations=classpath:db/data.sql,classpath:db/users.sql",
        "spring.sql.init.encoding=UTF-8"
})
@Import({LoginService.class, UserAdminService.class, LoginServiceTest.Beans.class, com.scb.monitoring.ReadOnlyWriteGuard.class})
@EnableConfigurationProperties(AppSecurityProperties.class)
class LoginServiceTest {

    @TestConfiguration
    static class Beans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }

    @Autowired LoginService service;
    @Autowired UserAdminService admin;

    private static SignupForm signup(String id) {
        SignupForm f = new SignupForm();
        f.setUserId(id);
        f.setUserNm("홍길동");
        f.setPassword("Test#2026a");
        f.setPasswordConfirm("Test#2026a");
        f.setDeptNm("Murex 운영");
        f.setReqReason("서버 모니터링 조회");
        return f;
    }

    @Test
    void signupIsPendingUntilApproved() {
        admin.signup(signup("hong01"));
        AppUser u = service.loadUserByUsername("hong01");
        assertEquals(AppUser.APPR_PENDING, u.getApprStatus());
        assertEquals(AppUser.ROLE_USER, u.getRoleCd());
        assertFalse(u.isEnabled(), "승인 전에는 로그인 불가");
        assertTrue(u.getPassword().startsWith("{bcrypt}"));
        assertEquals("pending", service.disabledReason("hong01"));
        assertEquals(1, admin.countPending());
        assertThrows(TopologyException.class, () -> admin.signup(signup("hong01")), "중복 아이디");

        admin.approve("hong01", "USER", "admin");
        u = service.loadUserByUsername("hong01");
        assertTrue(u.isEnabled());
        assertEquals("admin", u.getApprUser());
        assertEquals(0, admin.countPending());
        assertThrows(TopologyException.class, () -> admin.approve("hong01", "USER", "admin"), "이미 처리된 신청");
    }

    @Test
    void rejectNeedsReasonAndBlocksLogin() {
        admin.signup(signup("kim02"));
        assertThrows(TopologyException.class, () -> admin.reject("kim02", " ", "admin"));
        admin.reject("kim02", "소속 확인 불가", "admin");
        assertFalse(service.loadUserByUsername("kim02").isEnabled());
        assertEquals("rejected", service.disabledReason("kim02"));
    }

    @Test
    void lastAdminAndSelfAreProtected() {
        assertThrows(TopologyException.class, () -> admin.setUse("admin", false, "admin"), "자기 자신 중지 불가");
        assertThrows(TopologyException.class, () -> admin.changeRole("admin", "USER", "admin"), "자기 권한 내림 불가");
        assertThrows(TopologyException.class, () -> admin.delete("admin", "admin"), "자기 삭제 불가");

        admin.signup(signup("lee03"));
        admin.approve("lee03", "ADMIN", "admin");
        // 관리자가 2명이면 다른 관리자가 내릴 수 있음
        admin.changeRole("admin", "USER", "lee03");
        assertEquals(AppUser.ROLE_USER, service.loadUserByUsername("admin").getRoleCd());
        // 이제 lee03 이 마지막 관리자 → 다른 사람(일반 사용자 포함)이 내려도 거부
        assertThrows(TopologyException.class, () -> admin.changeRole("lee03", "USER", "admin"));
        assertThrows(TopologyException.class, () -> admin.delete("lee03", "admin"));
    }

    @Test
    void changePassword() {
        assertTrue(service.changePassword("admin", "wrong", "New#Pass99", "10.0.0.2").contains("올바르지 않습니다"));
        assertEquals(Integer.valueOf(1), service.loadUserByUsername("admin").getFailCnt());
        assertEquals("새 비밀번호가 현재 비밀번호와 같습니다.", service.changePassword("admin", "admin", "admin", "10.0.0.2"));

        assertNull(service.changePassword("admin", "admin", "New#Pass99", "10.0.0.2"));
        AppUser u = service.loadUserByUsername("admin");
        PasswordEncoder enc = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        assertTrue(enc.matches("New#Pass99", u.getPassword()));
        assertFalse(enc.matches("admin", u.getPassword()));
        assertEquals(Integer.valueOf(0), u.getFailCnt());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM tb_login_hist WHERE result_cd = 'PW_CHANGE'", Integer.class));

        // 승인 대기 계정은 비밀번호가 맞아도 변경 불가
        admin.signup(signup("park04"));
        assertTrue(service.changePassword("park04", "Test#2026a", "Other#Pass1", "10.0.0.2").contains("승인 대기"));
    }
    @Autowired JdbcTemplate jdbc;

    @Test
    void adminSeedUsesBcrypt() {
        AppUser u = service.loadUserByUsername("admin");
        assertEquals("관리자", u.getUserNm());
        assertTrue(u.isAdmin());
        assertTrue(u.isEnabled());
        assertTrue(u.isAccountNonLocked());
        assertTrue(u.getPassword().startsWith("{bcrypt}"));
        assertTrue(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches("admin", u.getPassword()));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("nobody"));
    }

    @Test
    void locksAfterFiveFailuresAndLogsHistory() {
        for (int i = 1; i <= 4; i++) assertFalse(service.passwordFailed("admin", "10.0.0.1"), "fail " + i);
        assertTrue(service.passwordFailed("admin", "10.0.0.1"), "5번째 실패에서 잠금");
        AppUser u = service.loadUserByUsername("admin");
        assertFalse(u.isAccountNonLocked());
        assertEquals(Integer.valueOf(5), u.getFailCnt());

        assertEquals(Integer.valueOf(4), jdbc.queryForObject("SELECT COUNT(*) FROM tb_login_hist WHERE result_cd = 'FAIL'", Integer.class));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM tb_login_hist WHERE result_cd = 'LOCKED'", Integer.class));
    }

    @Test
    void successResetsFailCount() {
        service.passwordFailed("admin", "10.0.0.1");
        service.passwordFailed("admin", "10.0.0.1");
        service.loginSucceeded("admin", "10.0.0.2");
        AppUser u = service.loadUserByUsername("admin");
        assertEquals(Integer.valueOf(0), u.getFailCnt());
        assertEquals("10.0.0.2", u.getLastLoginIp());
    }

    @Test
    void unknownUserOnlyLogsHistory() {
        assertFalse(service.passwordFailed("ghost", "10.0.0.9"));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM tb_login_hist WHERE user_id = 'ghost'", Integer.class));
        assertNull(jdbc.queryForObject("SELECT MAX(user_id) FROM tb_user WHERE user_id = 'ghost'", String.class));
    }
}
