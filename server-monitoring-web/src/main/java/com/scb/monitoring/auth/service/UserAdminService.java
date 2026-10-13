package com.scb.monitoring.auth.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.dto.SignupForm;
import com.scb.monitoring.auth.dto.UserView;
import com.scb.monitoring.auth.mapper.UserMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 사용자 가입 신청 / 관리자 승인·반려·잠금 해제·사용 중지·권한 변경·삭제.
 * <ul>
 *   <li>가입 신청은 항상 USER 권한, 승인 대기(PENDING) 로 등록 → 승인 전에는 로그인 불가</li>
 *   <li>자기 자신은 사용 중지 / 권한 내림 / 삭제할 수 없음</li>
 *   <li>마지막 활성 관리자는 사용 중지 / 권한 내림 / 삭제할 수 없음</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;

    private final com.scb.monitoring.menu.mapper.MenuMapper menuMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.scb.monitoring.menu.service.MenuService> menuService;

    public UserAdminService(UserMapper mapper, PasswordEncoder passwordEncoder,
                            com.scb.monitoring.menu.mapper.MenuMapper menuMapper,
                            org.springframework.beans.factory.ObjectProvider<com.scb.monitoring.menu.service.MenuService> menuService) {
        this.menuMapper = menuMapper;
        this.menuService = menuService;
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------ 가입 신청

    @Transactional(readOnly = true)
    public boolean exists(String userId) {
        return mapper.countUser(userId) > 0;
    }

    /** 가입 신청 등록 (입력 검증은 컨트롤러에서 끝난 상태) */
    @Transactional
    public void signup(SignupForm form) {
        if (exists(form.getUserId())) throw TopologyException.conflict("이미 사용 중인 아이디입니다.");
        AppUser u = new AppUser();
        u.setUserId(form.getUserId());
        u.setUserNm(form.getUserNm());
        u.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        u.setEmail(form.getEmail());
        u.setDeptNm(form.getDeptNm());
        u.setReqReason(form.getReqReason());
        mapper.insertSignup(u);
        log.info("SIGNUP REQUEST {} ({})", u.getUserId(), u.getUserNm());
    }

    // ------------------------------------------------------------ 관리자

    @Transactional(readOnly = true)
    public List<UserView> list(String apprStatus, String currentUserId) {
        String st = apprStatus == null || apprStatus.isEmpty() || "ALL".equals(apprStatus) ? null : apprStatus;
        List<UserView> out = new ArrayList<>();
        for (AppUser u : mapper.selectUserList(st)) out.add(UserView.of(u, currentUserId));
        return out;
    }

    @Transactional(readOnly = true)
    public int countPending() {
        return mapper.countPending();
    }

    @Transactional
    public void approve(String userId, String roleCd, String adminId) {
        String role = AppUser.ROLE_ADMIN.equals(roleCd) ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER;
        require(userId);
        if (mapper.approveUser(userId, role, adminId) == 0) {
            throw TopologyException.conflict("승인 대기 상태가 아닙니다. (이미 처리된 신청)");
        }
        log.info("USER APPROVED {} as {} by {}", userId, role, adminId);
    }

    @Transactional
    public void reject(String userId, String reason, String adminId) {
        if (reason == null || reason.trim().isEmpty()) throw TopologyException.badRequest("반려 사유를 입력하세요.");
        String r = reason.trim();
        if (r.length() > 200) r = r.substring(0, 200);
        require(userId);
        if (mapper.rejectUser(userId, r, adminId) == 0) {
            throw TopologyException.conflict("승인 대기 상태가 아닙니다. (이미 처리된 신청)");
        }
        log.info("USER REJECTED {} by {}", userId, adminId);
    }

    @Transactional
    public void unlock(String userId, String adminId) {
        require(userId);
        mapper.unlockUser(userId);
        log.info("USER UNLOCKED {} by {}", userId, adminId);
    }

    @Transactional
    public void setUse(String userId, boolean use, String adminId) {
        AppUser u = require(userId);
        if (!use) {
            if (userId.equals(adminId)) throw TopologyException.badRequest("자기 자신은 사용 중지할 수 없습니다.");
            guardLastAdmin(u, "사용 중지");
        }
        mapper.updateUseYn(userId, use ? "Y" : "N");
        log.info("USER {} {} by {}", use ? "ENABLED" : "DISABLED", userId, adminId);
    }

    @Transactional
    public void changeRole(String userId, String roleCd, String adminId) {
        if (!AppUser.ROLE_ADMIN.equals(roleCd) && !AppUser.ROLE_USER.equals(roleCd)) {
            throw TopologyException.badRequest("권한은 ADMIN / USER 중 하나입니다.");
        }
        AppUser u = require(userId);
        if (!AppUser.APPR_APPROVED.equals(u.getApprStatus())) throw TopologyException.badRequest("승인된 사용자만 권한을 바꿀 수 있습니다.");
        if (AppUser.ROLE_USER.equals(roleCd)) {
            if (userId.equals(adminId)) throw TopologyException.badRequest("자기 자신의 관리자 권한은 내릴 수 없습니다.");
            guardLastAdmin(u, "권한 변경");
        }
        mapper.updateRole(userId, roleCd);
        forgetMenus(userId);
        log.info("USER ROLE {} -> {} by {}", userId, roleCd, adminId);
    }

    @Transactional
    public void delete(String userId, String adminId) {
        AppUser u = require(userId);
        if (userId.equals(adminId)) throw TopologyException.badRequest("자기 자신은 삭제할 수 없습니다.");
        guardLastAdmin(u, "삭제");
        menuMapper.deleteUserMenus(userId);
        mapper.deleteUser(userId);
        forgetMenus(userId);
        log.info("USER DELETED {} by {}", userId, adminId);
    }

    /**
     * 관리자 계정 생성 : 바로 승인, 웹이 만든 임시 비밀번호 (화면에 한 번만 보여 줌) - 그 사용자는 첫 로그인 때 바꿔야 함.
     * @return 임시 비밀번호
     */
    @Transactional
    public String createByAdmin(com.scb.monitoring.auth.dto.UserCreateForm form, String adminId) {
        String role = AppUser.ROLE_ADMIN.equals(form.getRoleCd()) ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER;
        if (exists(form.getUserId())) throw TopologyException.badRequest("이미 사용 중인 아이디입니다 : " + form.getUserId());
        String temp = tempPassword(form.getUserId());
        AppUser u = new AppUser();
        u.setUserId(form.getUserId());
        u.setUserNm(form.getUserNm());
        u.setPasswordHash(passwordEncoder.encode(temp));
        u.setRoleCd(role);
        u.setEmail(form.getEmail());
        u.setDeptNm(form.getDeptNm());
        u.setReqReason("관리자 생성 (" + adminId + ")");
        u.setApprUser(adminId);
        mapper.insertByAdmin(u);
        log.info("USER CREATED {} role={} by {} (임시 비밀번호, 첫 로그인 변경)", u.getUserId(), role, adminId);
        return temp;
    }

    /** 임시 비밀번호 다시 발급 (잊었을 때) - 잠금도 풀고 첫 로그인 변경 필요로 */
    @Transactional
    public String resetTempPassword(String userId, String adminId) {
        AppUser u = require(userId);
        if (!AppUser.APPR_APPROVED.equals(u.getApprStatus())) throw TopologyException.badRequest("승인된 계정만 임시 비밀번호를 발급할 수 있습니다.");
        String temp = tempPassword(userId);
        mapper.updateTempPassword(userId, passwordEncoder.encode(temp));
        log.info("USER TEMP PASSWORD {} by {}", userId, adminId);
        return temp;
    }

    /** 임시 비밀번호 : 12자 - 대·소문자·숫자·특수문자 각 1자 이상, 헷갈리는 글자(0 O 1 l I) 제외, 아이디 포함 안 함 */
    static String tempPassword(String userId) {
        java.security.SecureRandom r = new java.security.SecureRandom();
        String up = "ABCDEFGHJKLMNPQRSTUVWXYZ", lo = "abcdefghijkmnpqrstuvwxyz", dg = "23456789", sp = "!@#$%^*-_+";
        String all = up + lo + dg + sp;
        while (true) {
            char[] c = new char[12];
            c[0] = up.charAt(r.nextInt(up.length()));
            c[1] = lo.charAt(r.nextInt(lo.length()));
            c[2] = dg.charAt(r.nextInt(dg.length()));
            c[3] = sp.charAt(r.nextInt(sp.length()));
            for (int i = 4; i < c.length; i++) c[i] = all.charAt(r.nextInt(all.length()));
            for (int i = c.length - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                char t = c[i];
                c[i] = c[j];
                c[j] = t;
            }
            String p = new String(c);
            if (userId == null || !p.toLowerCase().contains(userId.toLowerCase())) return p;
        }
    }

    private void forgetMenus(String userId) {
        com.scb.monitoring.menu.service.MenuService m = menuService.getIfAvailable();
        if (m != null) m.forgetUser(userId);
    }

    private AppUser require(String userId) {
        AppUser u = mapper.selectUser(userId);
        if (u == null) throw TopologyException.notFound("사용자를 찾을 수 없습니다: " + userId);
        return u;
    }

    /** 활성 관리자가 이 사용자 한 명뿐이면 막음 */
    private void guardLastAdmin(AppUser u, String action) {
        boolean activeAdmin = u.isAdmin() && "Y".equals(u.getUseYn()) && !"Y".equals(u.getLockYn())
                && AppUser.APPR_APPROVED.equals(u.getApprStatus());
        if (activeAdmin && mapper.countActiveAdmins() <= 1) {
            throw TopologyException.badRequest("마지막 관리자 계정은 " + action + "할 수 없습니다.");
        }
    }
}
