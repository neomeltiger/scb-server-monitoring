package com.scb.monitoring.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 비밀번호 변경 (/password 화면). 현재 비밀번호를 알아야 변경할 수 있습니다. */
public class PasswordChangeForm {

    @NotBlank(message = "아이디를 입력하세요.")
    @Size(max = 30, message = "아이디는 30자 이내입니다.")
    private String userId;

    @NotBlank(message = "현재 비밀번호를 입력하세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력하세요.")
    @Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상으로 입력하세요.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
             message = "새 비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다.")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인을 입력하세요.")
    private String newPasswordConfirm;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId == null ? null : userId.trim(); }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    public String getNewPasswordConfirm() { return newPasswordConfirm; }
    public void setNewPasswordConfirm(String newPasswordConfirm) { this.newPasswordConfirm = newPasswordConfirm; }

    /** 화면으로 되돌릴 때 비밀번호 값은 비움 */
    public void clearPasswords() {
        currentPassword = null;
        newPassword = null;
        newPasswordConfirm = null;
    }
}
