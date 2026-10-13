package com.scb.monitoring.auth.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.auth.domain.AppUser;

/** 로그인 사용자 / 가입 승인 Mapper. SQL: resources/mapper/auth/UserMapper.xml (ANSI SQL) */
@Mapper
public interface UserMapper {

    // ---- 로그인 ----
    AppUser selectUser(@Param("userId") String userId);

    int updateLoginSuccess(@Param("userId") String userId, @Param("clientIp") String clientIp);

    /** 실패 횟수 +1, 누적이 maxFail 이상이면 잠금 */
    int increaseFailCount(@Param("userId") String userId, @Param("maxFail") int maxFail);

    int insertLoginHist(@Param("userId") String userId, @Param("clientIp") String clientIp,
                        @Param("resultCd") String resultCd, @Param("message") String message);

    /** 비밀번호 변경 (실패 횟수 0 으로) */
    int updatePassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash);

    // ---- 가입 신청 ----
    int countUser(@Param("userId") String userId);

    /** 가입 신청 : PENDING, USER, 사용 Y */
    int insertSignup(AppUser user);

    // ---- 사용자 관리 (관리자) ----
    /** apprStatus 가 null 이면 전체 */
    List<AppUser> selectUserList(@Param("apprStatus") String apprStatus);

    int countPending();

    /** 승인된 활성 관리자 수 (마지막 관리자 보호) */
    int countActiveAdmins();

    int approveUser(@Param("userId") String userId, @Param("roleCd") String roleCd, @Param("apprUser") String apprUser);

    int rejectUser(@Param("userId") String userId, @Param("rejectReason") String rejectReason, @Param("apprUser") String apprUser);

    int unlockUser(@Param("userId") String userId);

    int updateUseYn(@Param("userId") String userId, @Param("useYn") String useYn);

    int updateRole(@Param("userId") String userId, @Param("roleCd") String roleCd);

    int deleteUser(@Param("userId") String userId);

    /** 관리자 계정 생성 (승인됨, 임시 비밀번호) */
    int insertByAdmin(AppUser user);

    /** 임시 비밀번호 다시 발급 (첫 로그인 변경 필요, 잠금 해제) */
    int updateTempPassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash);
}
