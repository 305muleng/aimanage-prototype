package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AccessController {
    @GetMapping("/access")
    public String access(HttpSession session, Model model) {
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", ((Role)session.getAttribute(SessionKeys.ROLE)).getLabel());
        return "access";
    }
}
