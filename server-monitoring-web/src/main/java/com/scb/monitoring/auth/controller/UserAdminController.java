package com.scb.monitoring.auth.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.scb.monitoring.auth.dto.UserView;
import com.scb.monitoring.auth.domain.AppUser;
import com.scb.monitoring.auth.service.UserAdminService;

/**
 * 사용자 관리 (가입 승인) - ADMIN 전용 (SecurityConfig : /admin/**, /api/admin/**).
 * <pre>
 *  GET    /admin/users                         화면
 *  GET    /api/admin/users?status=PENDING      목록 (ALL | PENDING | APPROVED | REJECTED)
 *  POST   /api/admin/users/{id}/approve        {"roleCd":"USER"}
 *  POST   /api/admin/users/{id}/reject         {"reason":"..."}
 *  POST   /api/admin/users/{id}/unlock
 *  PUT    /api/admin/users/{id}/use            {"use":true}
 *  PUT    /api/admin/users/{id}/role           {"roleCd":"ADMIN"}
 *  DELETE /api/admin/users/{id}
 * </pre>
 */
@Tag(name = "6. 사용자 관리", description = "가입 승인·잠금 해제·권한 (ADMIN 전용)")
@Controller
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @Hidden
    @GetMapping("/admin/users")
    public String page(Model model) {
        model.addAttribute("menu", "users");
        return "admin_users";
    }

    @Operation(summary = "사용자 목록")
    @GetMapping("/api/admin/users")
    @ResponseBody
    public Map<String, Object> list(@RequestParam(value = "status", required = false) String status, Authentication auth) {
        List<UserView> users = service.list(status, auth.getName());
        Map<String, Object> body = new HashMap<>();
        body.put("users", users);
        body.put("pendingCount", service.countPending());
        return body;
    }

    @Operation(summary = "가입 승인")
    @PostMapping("/api/admin/users/{userId}/approve")
    @ResponseBody
    public Map<String, Object> approve(@PathVariable String userId, @RequestBody(required = false) Map<String, Object> req,
                                       Authentication auth) {
        service.approve(userId, str(req, "roleCd"), auth.getName());
        return ok("승인되었습니다.");
    }

    @Operation(summary = "가입 반려")
    @PostMapping("/api/admin/users/{userId}/reject")
    @ResponseBody
    public Map<String, Object> reject(@PathVariable String userId, @RequestBody(required = false) Map<String, Object> req,
                                      Authentication auth) {
        service.reject(userId, str(req, "reason"), auth.getName());
        return ok("반려되었습니다.");
    }

    @Operation(summary = "잠금 해제")
    @PostMapping("/api/admin/users/{userId}/unlock")
    @ResponseBody
    public Map<String, Object> unlock(@PathVariable String userId, Authentication auth) {
        service.unlock(userId, auth.getName());
        return ok("잠금이 해제되었습니다.");
    }

    @Operation(summary = "사용 / 중지")
    @PutMapping("/api/admin/users/{userId}/use")
    @ResponseBody
    public Map<String, Object> use(@PathVariable String userId, @RequestBody Map<String, Object> req, Authentication auth) {
        boolean use = Boolean.TRUE.equals(req.get("use"));
        service.setUse(userId, use, auth.getName());
        return ok(use ? "사용으로 변경되었습니다." : "사용 중지되었습니다.");
    }

    @Operation(summary = "권한 변경 (ADMIN/USER)")
    @PutMapping("/api/admin/users/{userId}/role")
    @ResponseBody
    public Map<String, Object> role(@PathVariable String userId, @RequestBody Map<String, Object> req, Authentication auth) {
        service.changeRole(userId, str(req, "roleCd"), auth.getName());
        return ok("권한이 변경되었습니다.");
    }

    @Operation(summary = "계정 생성 (관리자) - 바로 승인, 임시 비밀번호를 한 번만 돌려줌 (그 사용자는 첫 로그인 때 변경)")
    @PostMapping("/api/admin/users")
    @ResponseBody
    public Map<String, Object> create(@javax.validation.Valid @RequestBody com.scb.monitoring.auth.dto.UserCreateForm form,
                                      Authentication auth) {
        String temp = service.createByAdmin(form, auth.getName());
        Map<String, Object> m = ok("계정을 만들었습니다.");
        m.put("userId", form.getUserId());
        m.put("roleCd", AppUser.ROLE_ADMIN.equals(form.getRoleCd()) ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER);
        m.put("tempPassword", temp);
        return m;
    }

    @Operation(summary = "임시 비밀번호 다시 발급 (관리자) - 잠금 해제, 첫 로그인 때 변경")
    @PostMapping("/api/admin/users/{userId}/temp-password")
    @ResponseBody
    public Map<String, Object> tempPassword(@PathVariable String userId, Authentication auth) {
        String temp = service.resetTempPassword(userId, auth.getName());
        Map<String, Object> m = ok("임시 비밀번호를 발급했습니다.");
        m.put("userId", userId);
        m.put("tempPassword", temp);
        return m;
    }

    @Operation(summary = "사용자 삭제")
    @DeleteMapping("/api/admin/users/{userId}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable String userId, Authentication auth) {
        service.delete(userId, auth.getName());
        return ok("삭제되었습니다.");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> ok(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("message", message);
        return m;
    }
}
