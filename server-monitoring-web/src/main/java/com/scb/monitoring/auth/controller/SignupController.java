package com.scb.monitoring.auth.controller;

import javax.validation.Valid;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.scb.monitoring.auth.dto.SignupForm;
import com.scb.monitoring.auth.service.UserAdminService;
import com.scb.monitoring.topology.service.TopologyException;

/** 사용자 가입 신청 화면 (/signup, 로그인 없이 접근). 등록 후 /login?signup 으로 이동 (PRG) */
@Controller
public class SignupController {

    private final UserAdminService userAdminService;

    public SignupController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("/signup")
    public String form(@ModelAttribute("form") SignupForm form, Authentication authentication) {
        if (loggedIn(authentication)) return "redirect:/";
        return "signup";
    }

    @PostMapping("/signup")
    public String submit(@Valid @ModelAttribute("form") SignupForm form, BindingResult result) {
        if (!result.hasFieldErrors("password") && !result.hasFieldErrors("passwordConfirm")
                && !form.getPassword().equals(form.getPasswordConfirm())) {
            result.rejectValue("passwordConfirm", "mismatch", "비밀번호 확인이 일치하지 않습니다.");
        }
        if (!result.hasFieldErrors("password") && !result.hasFieldErrors("userId")
                && form.getPassword().toLowerCase().contains(form.getUserId().toLowerCase())) {
            result.rejectValue("password", "containsId", "비밀번호에 아이디를 포함할 수 없습니다.");
        }
        if (!result.hasFieldErrors("userId") && userAdminService.exists(form.getUserId())) {
            result.rejectValue("userId", "duplicate", "이미 사용 중인 아이디입니다.");
        }
        if (result.hasErrors()) {
            form.setPassword(null);
            form.setPasswordConfirm(null);
            return "signup";
        }
        try {
            userAdminService.signup(form);
        } catch (TopologyException e) {
            // 동시에 같은 아이디로 신청한 경우
            result.rejectValue("userId", "duplicate", e.getMessage());
            form.setPassword(null);
            form.setPasswordConfirm(null);
            return "signup";
        }
        return "redirect:/login?signup";
    }

    private static boolean loggedIn(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }
}
