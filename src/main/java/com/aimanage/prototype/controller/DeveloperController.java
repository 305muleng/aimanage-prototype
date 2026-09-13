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

@Controller
public class DeveloperController {

    private final ProjectTaskService service;

    public DeveloperController(ProjectTaskService service) {
        this.service = service;
    }

    @GetMapping("/my-tasks")
    public String myTasks(HttpSession session, Model model) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";

        var tasks = service.myTasks(developerId);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "开发人员");
        model.addAttribute("tasks", tasks);
        model.addAttribute("statuses", TaskStatus.values());
        model.addAttribute("todoCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.TODO).count());
        model.addAttribute("doingCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count());
        model.addAttribute("doneCount", tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count());
        return "my-tasks";
    }

    @PostMapping("/my-tasks/{taskId}/status")
    public String updateStatus(@PathVariable Long taskId, @RequestParam TaskStatus status,
                               HttpSession session, RedirectAttributes redirect) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";
        try {
            service.updateStatus(developerId, taskId, status);
            redirect.addFlashAttribute("success", "任务状态已更新为“" + status.getLabel() + "”");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/my-tasks";
    }

    private Long requireDeveloper(HttpSession session) {
        Object id = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        return id instanceof Long && role == Role.DEVELOPER ? (Long) id : null;
    }
}
