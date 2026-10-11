package com.scb.monitoring.auth.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.scb.monitoring.auth.dto.PasswordChangeForm;
import com.scb.monitoring.auth.service.LoginService;

/**
 * 비밀번호 변경 (/password). 로그인 화면의 [비밀번호 변경] 링크 또는 상단 사용자 바에서 들어옵니다.
 * 로그인한 상태라면 본인 아이디로 고정되고, 변경 후 로그아웃 → 새 비밀번호로 다시 로그인합니다.
 */
@Controller
public class PasswordController {

    private final LoginService loginService;

    public PasswordController(LoginService loginService) {
        this.loginService = loginService;
    }

    @GetMapping("/password")
    public String form(@ModelAttribute("form") PasswordChangeForm form, Authentication auth, Model model) {
        if (loggedIn(auth)) form.setUserId(auth.getName());
        model.addAttribute("loggedIn", loggedIn(auth));
        return "password";
    }

    @PostMapping("/password")
    public String submit(@Valid @ModelAttribute("form") PasswordChangeForm form, BindingResult result,
                         Authentication auth, Model model, HttpServletRequest request, HttpServletResponse response) {
        boolean loggedIn = loggedIn(auth);
        if (loggedIn) form.setUserId(auth.getName());
        model.addAttribute("loggedIn", loggedIn);

        if (!result.hasFieldErrors("newPassword") && !result.hasFieldErrors("newPasswordConfirm")
                && !form.getNewPassword().equals(form.getNewPasswordConfirm())) {
            result.rejectValue("newPasswordConfirm", "mismatch", "새 비밀번호 확인이 일치하지 않습니다.");
        }
        if (!result.hasFieldErrors("newPassword") && !result.hasFieldErrors("userId")
                && form.getNewPassword().toLowerCase().contains(form.getUserId().toLowerCase())) {
            result.rejectValue("newPassword", "containsId", "비밀번호에 아이디를 포함할 수 없습니다.");
        }
        if (result.hasErrors()) {
            form.clearPasswords();
            return "password";
        }

        String error = loginService.changePassword(form.getUserId(), form.getCurrentPassword(),
                form.getNewPassword(), request.getRemoteAddr());
        if (error != null) {
            result.reject("change", error);
            form.clearPasswords();
            return "password";
        }
        // 기존 세션 종료 → 새 비밀번호로 로그인
        if (loggedIn) new SecurityContextLogoutHandler().logout(request, response, auth);
        return "redirect:/login?pwchanged";
    }

    private static boolean loggedIn(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }
}
