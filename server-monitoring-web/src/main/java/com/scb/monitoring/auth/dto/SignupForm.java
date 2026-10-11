package com.scb.monitoring.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 사용자 가입 신청 (/signup 화면 입력 폼). 신청 후 관리자가 승인해야 로그인할 수 있습니다. */
public class SignupForm {

    @NotBlank(message = "아이디를 입력하세요.")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{3,29}$",
             message = "아이디는 영문으로 시작하는 4~30자 (영문, 숫자, . _ -) 로 입력하세요.")
    private String userId;

    @NotBlank(message = "이름을 입력하세요.")
    @Size(max = 50, message = "이름은 50자 이내로 입력하세요.")
    private String userNm;

    @NotBlank(message = "비밀번호를 입력하세요.")
    @Size(min = 8, max = 100, message = "비밀번호는 8자 이상으로 입력하세요.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
             message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다.")
    private String password;

    @NotBlank(message = "비밀번호 확인을 입력하세요.")
    private String passwordConfirm;

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 100, message = "이메일은 100자 이내로 입력하세요.")
    private String email;

    @Size(max = 50, message = "부서는 50자 이내로 입력하세요.")
    private String deptNm;

    @NotBlank(message = "신청 사유를 입력하세요.")
    @Size(max = 200, message = "신청 사유는 200자 이내로 입력하세요.")
    private String reqReason;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId == null ? null : userId.trim(); }
    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm == null ? null : userNm.trim(); }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPasswordConfirm() { return passwordConfirm; }
    public void setPasswordConfirm(String passwordConfirm) { this.passwordConfirm = passwordConfirm; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null || email.trim().isEmpty() ? null : email.trim(); }
    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm == null || deptNm.trim().isEmpty() ? null : deptNm.trim(); }
    public String getReqReason() { return reqReason; }
    public void setReqReason(String reqReason) { this.reqReason = reqReason == null ? null : reqReason.trim(); }
}
