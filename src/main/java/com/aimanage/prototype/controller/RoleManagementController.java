package com.aimanage.prototype.controller;
import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequestMapping("/admin/roles")
public class RoleManagementController {
    private final RoleManagementService service;
    public RoleManagementController(RoleManagementService service) { this.service=service; }
    private Long adminId(HttpSession session) {
        return session.getAttribute(SessionKeys.ROLE)==Role.SYSTEM_ADMIN && session.getAttribute(SessionKeys.USER_ID) instanceof Long id ? id:null;
    }
    @GetMapping public String page(HttpSession session,Model model) {
        Long id=adminId(session);if(id==null)return "redirect:/login";
        fill(session,model,id);return "roles";
    }
    private void fill(HttpSession session,Model model,Long id) {
        if(session.getAttribute("roleCsrf")==null) session.setAttribute("roleCsrf",java.util.UUID.randomUUID().toString());
        model.addAttribute("roleCsrf",session.getAttribute("roleCsrf"));
        model.addAttribute("roles",service.roles(id));model.addAttribute("users",service.users(id));
        model.addAttribute("accessTypes",Role.values());model.addAttribute("displayName",session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel","系统管理员");
    }
    @PostMapping public String create(HttpSession session,Model model,RedirectAttributes redirect,
            @RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String description,
            @RequestParam(defaultValue="") String accessType,@RequestParam(defaultValue="") String csrfToken) {
        Long id=adminId(session);if(id==null)return "redirect:/login";
        try {
            checkToken(session,csrfToken);
            Role type;
            try { type=Role.valueOf(accessType); }catch(IllegalArgumentException ex){throw new BusinessException("请选择有效的权限类型");}
            service.create(id,name,description,type);
            redirect.addFlashAttribute("success","角色已创建，可在用户列表中分配");return "redirect:/admin/roles";
        }catch(BusinessException ex) {
            fill(session,model,id);model.addAttribute("error",ex.getMessage());model.addAttribute("draftName",name);
            model.addAttribute("draftDescription",description);model.addAttribute("draftAccessType",accessType);return "roles";
        }
    }
    @PostMapping("/users/{userId}") public String assign(HttpSession session,RedirectAttributes redirect,
            @PathVariable Long userId,@RequestParam Long roleId,@RequestParam(defaultValue="") String csrfToken) {
        Long id=adminId(session);if(id==null)return "redirect:/login";
        try {
            checkToken(session,csrfToken);service.assign(id,userId,roleId);
            redirect.addFlashAttribute("success","用户角色已更新，下次请求立即按新权限生效");
        }catch(BusinessException ex){redirect.addFlashAttribute("error",ex.getMessage());}
        return "redirect:/admin/roles";
    }
    private void checkToken(HttpSession session,String token) {
        if(token.isBlank()||!token.equals(session.getAttribute("roleCsrf")))throw new BusinessException("页面已失效，请刷新后重新操作");
    }
}
