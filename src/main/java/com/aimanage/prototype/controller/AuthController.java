package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.service.AuthService;
import com.aimanage.prototype.service.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping({"/", "/login"})
    public String loginPage(HttpSession session) {
        if (session.getAttribute(SessionKeys.USER_ID) != null) {
            return redirectForRole((Role) session.getAttribute(SessionKeys.ROLE));
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        HttpSession session, Model model) {
        try {
            User user = authService.authenticate(username, password);
            session.setAttribute(SessionKeys.USER_ID, user.getId());
            session.setAttribute(SessionKeys.DISPLAY_NAME, user.getDisplayName());
            session.setAttribute(SessionKeys.ROLE, user.getRole());
            return redirectForRole(user.getRole());
        } catch (BusinessException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("username", username);
            return "login";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    private String redirectForRole(Role role) {
        return role == Role.PROJECT_MANAGER ? "redirect:/manager" : "redirect:/my-tasks";
    }
}
