package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.service.AuthService;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.service.LoginAttemptLimiter;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 处理统一登录、按角色跳转和退出登录。 */
@Controller
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptLimiter limiter;
    private final boolean demoEnabled;

    public AuthController(AuthService authService, LoginAttemptLimiter limiter,
                          @Value("${aimanage.demo.enabled:false}") boolean demoEnabled) {
        this.authService = authService;
        this.limiter = limiter;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping({"/", "/login"})
    public String loginPage(HttpSession session, Model model) {
        if (session.getAttribute(SessionKeys.USER_ID) != null) {
            return redirectForRole((Role) session.getAttribute(SessionKeys.ROLE));
        }
        model.addAttribute("demoEnabled", demoEnabled);
        return "login";
    }

    /** 登录成功后将最小身份信息保存到会话，并根据角色进入对应工作台。 */
    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
                        HttpServletRequest request, HttpSession session, Model model) {
        try {
            limiter.check(username, request.getRemoteAddr());
            User user = authService.authenticate(username, password);
            limiter.succeeded(username, request.getRemoteAddr());
            request.changeSessionId();
            session.setAttribute(SessionKeys.USER_ID, user.getId());
            session.setAttribute(SessionKeys.DISPLAY_NAME, user.getDisplayName());
            session.setAttribute(SessionKeys.ROLE, user.getRole());
            session.setAttribute("managedRoleName", user.getManagedRoleName());
            return redirectForRole(user.getRole());
        } catch (BusinessException ex) {
            if (!ex.getMessage().contains("登录失败次数过多")) limiter.failed(username, request.getRemoteAddr());
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("username", username);
            model.addAttribute("demoEnabled", demoEnabled);
            return "login";
        }
    }

    @GetMapping("/register")
    public String registerPage() { return "register"; }

    @PostMapping("/register")
    public String register(@RequestParam(defaultValue = "") String username,
                           @RequestParam(defaultValue = "") String displayName,
                           @RequestParam(defaultValue = "") String password,
                           @RequestParam(defaultValue = "") String confirmPassword,
                           Model model, RedirectAttributes redirect) {
        try {
            authService.register(username, displayName, password, confirmPassword);
            redirect.addFlashAttribute("success", "注册成功，请使用新账号登录");
            redirect.addFlashAttribute("username", username.trim());
            return "redirect:/login";
        } catch (BusinessException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("username", username);
            model.addAttribute("displayName", displayName);
            return "register";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    /** 将领域角色转换为对应的首页地址。 */
    private String redirectForRole(Role role) {
        if (role == Role.SYSTEM_ADMIN) return "redirect:/admin/roles";
        return role == Role.PROJECT_MANAGER ? "redirect:/manager" : "redirect:/my-tasks";
    }
}
