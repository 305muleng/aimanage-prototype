package com.aimanage.prototype.controller;

import com.aimanage.prototype.service.ProfileService;
import com.aimanage.prototype.service.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.UUID;

@Controller
public class ProfileController {
    private static final String TOKEN = "profileCsrfToken";
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping("/account")
    public String account(HttpSession session, Model model) {
        if (!(session.getAttribute(SessionKeys.USER_ID) instanceof Long id)) return "redirect:/login";
        try {
            var profile = profiles.get(id);
            fill(session, model, profile);
            return "account";
        } catch (BusinessException ex) { session.invalidate(); return "redirect:/login"; }
    }

    @PostMapping("/account")
    public String update(HttpSession session, Model model, RedirectAttributes redirect,
                         @RequestParam(defaultValue = "") String displayName,
                         @RequestParam(defaultValue = "") String email,
                         @RequestParam(defaultValue = "") String phone,
                         @RequestParam(defaultValue = "") String bio,
                         @RequestParam(defaultValue = "") String csrfToken) {
        if (!(session.getAttribute(SessionKeys.USER_ID) instanceof Long id)) return "redirect:/login";
        ProfileService.Profile existing;
        try { existing = profiles.get(id); }
        catch (BusinessException ex) { session.invalidate(); return "redirect:/login"; }
        if (csrfToken.isBlank() || !csrfToken.equals(session.getAttribute(TOKEN))) {
            fill(session, model, existing);
            model.addAttribute("error", "页面已失效，请刷新后重新保存");
            return "account";
        }
        try {
            var saved = profiles.update(id, displayName, email, phone, bio);
            session.setAttribute(SessionKeys.DISPLAY_NAME, saved.displayName());
            redirect.addFlashAttribute("success", "个人资料已保存");
            return "redirect:/account";
        } catch (BusinessException ex) {
            fill(session, model, new ProfileService.Profile(existing.username(), existing.role(), displayName, email, phone, bio));
            model.addAttribute("error", ex.getMessage());
            return "account";
        }
    }

    private void fill(HttpSession session, Model model, ProfileService.Profile profile) {
        if (session.getAttribute(TOKEN) == null) session.setAttribute(TOKEN, UUID.randomUUID().toString());
        model.addAttribute("csrfToken", session.getAttribute(TOKEN));
        model.addAttribute("profile", profile);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", profile.role().getLabel());
    }
}
