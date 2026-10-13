package com.scb.monitoring.auth.domain;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * tb_user : 로그인 사용자. Spring Security 의 UserDetails 로 그대로 사용합니다.
 *  - appr_status : PENDING(가입 승인 대기) / APPROVED(승인) / REJECTED(반려) - 승인된 계정만 로그인
 *  - use_yn = N  → 비활성(DisabledException)
 *  - lock_yn = Y → 잠금(LockedException, 연속 로그인 실패)
 */
public class AppUser implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String APPR_PENDING = "PENDING";
    public static final String APPR_APPROVED = "APPROVED";
    public static final String APPR_REJECTED = "REJECTED";

    private String userId;
    private String userNm;
    private String passwordHash;
    private String roleCd;
    private String useYn;
    private String lockYn;
    private Integer failCnt;
    private Timestamp lastLoginDt;
    private String lastLoginIp;
    private String apprStatus;
    private String email;
    private String deptNm;
    private String reqReason;
    private String apprUser;
    private Timestamp apprDt;
    private String rejectReason;
    private Timestamp regDt;
    /** Y = 관리자가 만든 임시 비밀번호 - 첫 로그인 때 바꿔야 다른 화면 사용 */
    private String pwChangeYn = "N";
    /** Y = 사용자별 메뉴 권한 (tb_user_menu) 사용 */
    private String menuCustomYn = "N";

    /** 상단 사용자 표시 : 관리자 / 사용자 */
    public String getRoleLabel() { return ROLE_ADMIN.equals(roleCd) ? "관리자" : "사용자"; }

    public boolean isAdmin() { return ROLE_ADMIN.equals(roleCd); }

    // ---- UserDetails ----
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + roleCd));
    }

    @Override public String getUsername() { return userId; }
    @Override public String getPassword() { return passwordHash; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !"Y".equals(lockYn); }
    @Override public boolean isCredentialsNonExpired() { return true; }
    /** 사용 중이고 가입이 승인된 계정만 로그인 가능 */
    @Override public boolean isEnabled() { return "Y".equals(useYn) && APPR_APPROVED.equals(apprStatus); }

    /** 세션에 저장되므로 비밀번호 해시는 인증 후 지웁니다. (Spring Security 가 자동 호출) */
    @Override
    public void eraseCredentials() { this.passwordHash = null; }

    // ---- getter / setter ----
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRoleCd() { return roleCd; }
    public void setRoleCd(String roleCd) { this.roleCd = roleCd; }
    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }
    public String getLockYn() { return lockYn; }
    public void setLockYn(String lockYn) { this.lockYn = lockYn; }
    public Integer getFailCnt() { return failCnt; }
    public void setFailCnt(Integer failCnt) { this.failCnt = failCnt; }
    public Timestamp getLastLoginDt() { return lastLoginDt; }
    public void setLastLoginDt(Timestamp lastLoginDt) { this.lastLoginDt = lastLoginDt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }
    public String getApprStatus() { return apprStatus; }
    public void setApprStatus(String apprStatus) { this.apprStatus = apprStatus; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm; }
    public String getReqReason() { return reqReason; }
    public void setReqReason(String reqReason) { this.reqReason = reqReason; }
    public String getApprUser() { return apprUser; }
    public void setApprUser(String apprUser) { this.apprUser = apprUser; }
    public Timestamp getApprDt() { return apprDt; }
    public void setApprDt(Timestamp apprDt) { this.apprDt = apprDt; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Timestamp getRegDt() { return regDt; }
    public void setRegDt(Timestamp regDt) { this.regDt = regDt; }

    public boolean isPwChangeRequired() { return "Y".equals(pwChangeYn); }
    public String getPwChangeYn() { return pwChangeYn; }
    public void setPwChangeYn(String pwChangeYn) { this.pwChangeYn = pwChangeYn; }
    public String getMenuCustomYn() { return menuCustomYn; }
    public void setMenuCustomYn(String menuCustomYn) { this.menuCustomYn = menuCustomYn; }
}
