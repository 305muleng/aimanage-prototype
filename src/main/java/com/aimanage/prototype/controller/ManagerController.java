package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Project;
import com.aimanage.prototype.domain.ProjectMember;
import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.service.ProjectTaskService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final ProjectTaskService service;

    public ManagerController(ProjectTaskService service) {
        this.service = service;
    }

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        Long managerId = requireManager(session);
        if (managerId == null) return "redirect:/login";

        List<ProjectView> projectViews = service.projectsForManager(managerId).stream()
                .map(project -> new ProjectView(project, service.members(project.getId()),
                        service.projectTasks(project.getId())))
                .toList();

        long taskCount = service.taskCount(managerId);
        long doneCount = service.doneTaskCount(managerId);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "项目负责人");
        model.addAttribute("projects", projectViews);
        model.addAttribute("developers", service.developers());
        model.addAttribute("projectCount", projectViews.size());
        model.addAttribute("taskCount", taskCount);
        model.addAttribute("doneCount", doneCount);
        model.addAttribute("progress", taskCount == 0 ? 0 : Math.round(doneCount * 100.0 / taskCount));
        return "manager-dashboard";
    }

    @PostMapping("/projects")
    public String createProject(@RequestParam String name, @RequestParam(required = false) String description,
                                HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            service.createProject((Long) session.getAttribute(SessionKeys.USER_ID), name, description);
            redirect.addFlashAttribute("success", "项目创建成功，可以继续添加成员和任务");
        });
    }

    @PostMapping("/projects/{projectId}/members")
    public String addMember(@PathVariable Long projectId, @RequestParam Long userId,
                            HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            service.addMember((Long) session.getAttribute(SessionKeys.USER_ID), projectId, userId);
            redirect.addFlashAttribute("success", "成员已加入项目");
        });
    }

    @PostMapping("/projects/{projectId}/tasks")
    public String createTask(@PathVariable Long projectId, @RequestParam String title,
                             @RequestParam(required = false) String description,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                             @RequestParam Long assigneeId, HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            service.createTask((Long) session.getAttribute(SessionKeys.USER_ID), projectId,
                    title, description, dueDate, assigneeId);
            redirect.addFlashAttribute("success", "任务已创建并分配");
        });
    }

    @PostMapping("/tasks/{taskId}/edit")
    public String editTask(@PathVariable Long taskId, @RequestParam String title,
                           @RequestParam(required = false) String description,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                           @RequestParam Long assigneeId, HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            service.editTask((Long) session.getAttribute(SessionKeys.USER_ID), taskId,
                    title, description, dueDate, assigneeId);
            redirect.addFlashAttribute("success", "任务修改成功");
        });
    }

    private String execute(HttpSession session, RedirectAttributes redirect, Runnable action) {
        if (requireManager(session) == null) return "redirect:/login";
        try {
            action.run();
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    private Long requireManager(HttpSession session) {
        Object id = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        return id instanceof Long && role == Role.PROJECT_MANAGER ? (Long) id : null;
    }

    public record ProjectView(Project project, List<ProjectMember> members, List<Task> tasks) {
    }
}
