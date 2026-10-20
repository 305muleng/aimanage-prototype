package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.TaskStatus;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.service.ProjectTaskService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 处理开发人员“我的任务”查询及本人任务状态更新。 */
@Controller
public class DeveloperController {

    private final ProjectTaskService service;

    public DeveloperController(ProjectTaskService service) {
        this.service = service;
    }

    /** 仅返回当前登录开发人员被分配的任务。 */
    @GetMapping("/my-tasks")
    public String myTasks(HttpSession session, Model model) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";

        var tasks = service.myTasks(developerId);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "开发人员");
        model.addAttribute("tasks", tasks);
        model.addAttribute("editableTaskIds", tasks.stream().filter(t -> service.activeMember(t.getProject().getId(), developerId)).map(t -> t.getId()).toList());
        model.addAttribute("statuses", TaskStatus.values());
        if (session.getAttribute("myTasksCsrf") == null) session.setAttribute("myTasksCsrf", java.util.UUID.randomUUID().toString());
        model.addAttribute("csrfToken", session.getAttribute("myTasksCsrf"));
        model.addAttribute("todoCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.TODO).count());
        model.addAttribute("doingCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count());
        model.addAttribute("doneCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count());
        return "my-tasks";
    }

    @PostMapping("/my-tasks/{taskId}/status")
    public String updateStatus(@PathVariable Long taskId, @RequestParam TaskStatus status,
                               @RequestParam(defaultValue = "") String csrfToken, @RequestParam(required = false) Long version,
                               @RequestParam(defaultValue = "") String note,
                               HttpSession session, RedirectAttributes redirect) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";
        try {
            if (csrfToken.isBlank() || !csrfToken.equals(session.getAttribute("myTasksCsrf")))
                throw new BusinessException("页面已失效，请刷新后重新操作");
            service.updateStatus(developerId, taskId, status, version, note);
            redirect.addFlashAttribute("success", "任务状态已更新为“" + status.getLabel() + "”");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/my-tasks";
    }

    @GetMapping("/projects")
    public String projects(HttpSession session, Model model) {
        if (session.getAttribute(SessionKeys.ROLE) == Role.PROJECT_MANAGER) return "redirect:/manager";
        Long id = requireDeveloper(session);
        if (id == null) return "redirect:/login";
        model.addAttribute("projects", service.projectsForDeveloper(id));
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "开发人员");
        return "member-projects";
    }

    /** 校验当前会话是否属于开发人员。 */
    private Long requireDeveloper(HttpSession session) {
        Object id = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        return id instanceof Long && role == Role.DEVELOPER ? (Long) id : null;
    }
}
