package com.scb.monitoring.auth.dto;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import com.scb.monitoring.auth.domain.AppUser;

/** 사용자 관리 화면 응답 (비밀번호 해시는 내보내지 않음) */
public class UserView {

    private String userId;
    private String userNm;
    private String roleCd;
    private String roleLabel;
    private String useYn;
    private String lockYn;
    private int failCnt;
    private String apprStatus;
    private String apprLabel;
    private String email;
    private String deptNm;
    private String reqReason;
    private String apprUser;
    private String apprDt;
    private String rejectReason;
    private String regDt;
    private String lastLoginDt;
    private String lastLoginIp;
    private boolean me;

    public static UserView of(AppUser u, String currentUserId) {
        UserView v = new UserView();
        v.userId = u.getUserId();
        v.userNm = u.getUserNm();
        v.roleCd = u.getRoleCd();
        v.roleLabel = u.getRoleLabel();
        v.useYn = u.getUseYn();
        v.lockYn = u.getLockYn();
        v.failCnt = u.getFailCnt() == null ? 0 : u.getFailCnt();
        v.apprStatus = u.getApprStatus();
        v.apprLabel = AppUser.APPR_PENDING.equals(u.getApprStatus()) ? "승인 대기"
                : AppUser.APPR_REJECTED.equals(u.getApprStatus()) ? "반려" : "승인";
        v.email = u.getEmail();
        v.deptNm = u.getDeptNm();
        v.reqReason = u.getReqReason();
        v.apprUser = u.getApprUser();
        v.apprDt = dateTime(u.getApprDt());
        v.rejectReason = u.getRejectReason();
        v.regDt = dateTime(u.getRegDt());
        v.lastLoginDt = dateTime(u.getLastLoginDt());
        v.lastLoginIp = u.getLastLoginIp();
        v.me = u.getUserId().equals(currentUserId);
        return v;
    }

    private static String dateTime(Timestamp t) {
        return t == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
    }

    public String getUserId() { return userId; }
    public String getUserNm() { return userNm; }
    public String getRoleCd() { return roleCd; }
    public String getRoleLabel() { return roleLabel; }
    public String getUseYn() { return useYn; }
    public String getLockYn() { return lockYn; }
    public int getFailCnt() { return failCnt; }
    public String getApprStatus() { return apprStatus; }
    public String getApprLabel() { return apprLabel; }
    public String getEmail() { return email; }
    public String getDeptNm() { return deptNm; }
    public String getReqReason() { return reqReason; }
    public String getApprUser() { return apprUser; }
    public String getApprDt() { return apprDt; }
    public String getRejectReason() { return rejectReason; }
    public String getRegDt() { return regDt; }
    public String getLastLoginDt() { return lastLoginDt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public boolean isMe() { return me; }
}
