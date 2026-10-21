package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDate;

@Controller
@RequestMapping("/tasks")
public class TaskCollaborationController {
    private final TaskCollaborationService service;
    private final ProjectTaskService core;
    private static final String TOKEN = "taskCsrf";
    public TaskCollaborationController(TaskCollaborationService service, ProjectTaskService core) {
        this.service = service; this.core = core;
    }

    @GetMapping
    public String board(HttpSession session, Model model, RedirectAttributes redirect,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean blocked,
            @RequestParam(defaultValue = "updated") String sort) {
        identity(session, model);
        var projects = service.visibleProjects(uid(session));
        model.addAttribute("projects", projects);
        model.addAttribute("statuses", TaskStatus.values());
        model.addAttribute("priorities", Priority.values());
        Long selected = projectId == null && !projects.isEmpty() ? projects.get(0).id() : projectId;
        model.addAttribute("projectId", selected);
        var filter = new TaskCollaborationService.Filter(keyword, assigneeId, status, priority, from, to, blocked, sort);
        model.addAttribute("filter", filter);
        try {
            if (selected != null) {
                model.addAttribute("tasks", service.search(uid(session), selected, filter));
                model.addAttribute("members", core.members(uid(session), selected));
            }
        } catch (BusinessException ex) {
            // 不在错误页中渲染未授权项目的成员或任务。
            if (projects.stream().noneMatch(p -> p.id().equals(selected))) {
                redirect.addFlashAttribute("error", ex.getMessage());
                return "redirect:/tasks";
            }
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("tasks", java.util.List.of());
            model.addAttribute("members", core.members(uid(session), selected));
        }
        return "task-board";
    }

    @GetMapping("/{taskId}")
    public String detail(@PathVariable Long taskId, HttpSession session, Model model, RedirectAttributes redirect) {
        try {
            identity(session, model);
            var detail = service.detail(uid(session), taskId);
            model.addAttribute("detail", detail);
            model.addAttribute("workspaceName", detail.task().projectName());
            model.addAttribute("statuses", TaskStatus.values());
            return "task-detail";
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/tasks";
        }
    }

    @PostMapping("/{taskId}/comments")
    public String comment(@PathVariable Long taskId, @RequestParam(defaultValue = "") String content,
                          @RequestParam(defaultValue = "") String csrfToken, HttpSession session, RedirectAttributes redirect) {
        return write(taskId, csrfToken, session, redirect, () -> service.comment(uid(session), taskId, content), "评论已发表");
    }
    @PostMapping("/{taskId}/block")
    public String block(@PathVariable Long taskId, @RequestParam boolean blocked,
                        @RequestParam(defaultValue = "") String reason, @RequestParam(defaultValue = "") String csrfToken,
                        @RequestParam(required = false) Long version,
                        HttpSession session, RedirectAttributes redirect) {
        return write(taskId, csrfToken, session, redirect, () -> service.block(uid(session), taskId, blocked, reason, version), "阻塞标记已更新");
    }
    @PostMapping("/{taskId}/dependencies")
    public String dependency(@PathVariable Long taskId, @RequestParam Long predecessorId,
                             @RequestParam(defaultValue = "false") boolean remove,
                             @RequestParam(defaultValue = "") String csrfToken, HttpSession session, RedirectAttributes redirect) {
        return write(taskId, csrfToken, session, redirect,
                () -> service.dependency(uid(session), taskId, predecessorId, remove), "依赖关系已更新");
    }
    @PostMapping("/{taskId}/status")
    public String status(@PathVariable Long taskId, @RequestParam TaskStatus status,
                         @RequestParam(required = false) Long version, @RequestParam(defaultValue = "") String note,
                         @RequestParam(defaultValue = "") String csrfToken, HttpSession session, RedirectAttributes redirect) {
        return write(taskId, csrfToken, session, redirect, () -> service.status(uid(session), taskId, status, version, note), "任务状态已更新");
    }

    /** 所有新增写入入口统一校验会话令牌，身份和项目范围由业务服务再次校验。 */
    private String write(Long taskId, String token, HttpSession session, RedirectAttributes redirect,
                         Runnable action, String message) {
        try {
            if (token.isBlank() || !token.equals(session.getAttribute(TOKEN))) throw new BusinessException("页面已失效，请刷新后重试");
            action.run();
            redirect.addFlashAttribute("success", message);
        } catch (BusinessException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/tasks/" + taskId;
    }
    private Long uid(HttpSession session) { return (Long) session.getAttribute(SessionKeys.USER_ID); }
    /** AI 模块可读取已授权项目内的任务上下文；此接口不调用模型，也不返回用户账号或密码。 */
    @GetMapping("/{taskId}/context")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> context(@PathVariable Long taskId, HttpSession session) {
        try {
            var detail = service.detail(uid(session), taskId);
            return org.springframework.http.ResponseEntity.ok(new TaskContext(detail.task(), detail.description(),
                    detail.predecessors(), detail.successors(), detail.entries()));
        } catch (BusinessException ex) {
            return org.springframework.http.ResponseEntity.status(
                    ex.getMessage().equals("无权访问该项目任务") ? 403 : 404)
                    .body(java.util.Map.of("error", ex.getMessage()));
        }
    }
    public record TaskContext(TaskCollaborationService.TaskView task, String description,
                              java.util.List<TaskCollaborationService.TaskView> predecessors,
                              java.util.List<TaskCollaborationService.TaskView> successors,
                              java.util.List<TaskCollaborationService.EntryView> entries) {}
    private void identity(HttpSession session, Model model) {
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", ((Role) session.getAttribute(SessionKeys.ROLE)).getLabel());
        if (session.getAttribute(TOKEN) == null) session.setAttribute(TOKEN, java.util.UUID.randomUUID().toString());
        model.addAttribute("csrfToken", session.getAttribute(TOKEN));
    }
}
