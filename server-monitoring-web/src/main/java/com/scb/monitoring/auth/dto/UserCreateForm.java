package com.scb.monitoring.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 관리자 계정 생성 (POST /api/admin/users) - 비밀번호는 웹이 임시로 만들고, 그 사용자는 첫 로그인 때 바꿈 */
public class UserCreateForm {

    @NotBlank(message = "아이디를 입력하세요.")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{3,29}$",
             message = "아이디는 영문으로 시작하는 4~30자 (영문·숫자·. _ -)로 입력하세요.")
    private String userId;

    @NotBlank(message = "이름을 입력하세요.")
    @Size(max = 50, message = "이름은 50자 이내로 입력하세요.")
    private String userNm;

    @Size(max = 50, message = "부서는 50자 이내로 입력하세요.")
    private String deptNm;

    @Size(max = 100, message = "이메일은 100자 이내로 입력하세요.")
    private String email;

    /** ADMIN / USER (기본 USER) */
    private String roleCd = "USER";

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId == null ? null : userId.trim(); }
    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm == null ? null : userNm.trim(); }
    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm == null || deptNm.trim().isEmpty() ? null : deptNm.trim(); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null || email.trim().isEmpty() ? null : email.trim(); }
    public String getRoleCd() { return roleCd; }
    public void setRoleCd(String roleCd) { this.roleCd = roleCd; }
}
