package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.TaskStatus;
import com.aimanage.prototype.domain.Priority;
import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.domain.Project;
import com.aimanage.prototype.domain.ProjectMember;
import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.service.ProjectTaskService;
import com.aimanage.prototype.service.CalendarGrid;
import com.aimanage.prototype.service.ReminderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 处理开发人员“我的任务”查询及本人任务状态更新。 */
@Controller
public class DeveloperController {

    private final ProjectTaskService service;
    private final ReminderService reminders;

    public DeveloperController(ProjectTaskService service, ReminderService reminders) {
        this.service = service;
        this.reminders = reminders;
    }

    /** 仅返回当前登录开发人员被分配的任务。 */
    @GetMapping("/my-tasks")
    public String myTasks(HttpSession session, Model model,
                          @RequestParam(required = false) Long projectId,
                          @RequestParam(required = false) TaskStatus status,
                          @RequestParam(required = false) Priority priority,
                          @RequestParam(defaultValue = "updated") String sort,
                          @RequestParam(defaultValue = "list") String view,
                          @RequestParam(required = false) String month,
                          @RequestParam(required = false) String date) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";

        var allTasks = service.myTasks(developerId);
        if (!java.util.Set.of("updated", "due", "priority").contains(sort)) sort = "updated";
        java.util.Comparator<com.aimanage.prototype.domain.Task> order = switch (sort) {
            case "due" -> java.util.Comparator.comparing(com.aimanage.prototype.domain.Task::getDueDate,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
            case "priority" -> java.util.Comparator.comparing(com.aimanage.prototype.domain.Task::getPriority).reversed();
            default -> java.util.Comparator.comparing(com.aimanage.prototype.domain.Task::getUpdatedAt).reversed();
        };
        var tasks = allTasks.stream()
                .filter(t -> projectId == null || t.getProject().getId().equals(projectId))
                .filter(t -> status == null || t.getStatus() == status)
                .filter(t -> priority == null || t.getPriority() == priority)
                .sorted(order.thenComparing(com.aimanage.prototype.domain.Task::getId)).toList();
        model.addAttribute("projectOptions", service.projectsForDeveloper(developerId));
        model.addAttribute("selectedProjectId", projectId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedPriority", priority);
        model.addAttribute("selectedSort", sort);
        model.addAttribute("priorities", Priority.values());
        boolean weekView = "week".equals(view);
        boolean calendarView = "calendar".equals(view) || weekView;
        YearMonth selectedMonth;
        try { selectedMonth = month == null ? YearMonth.now() : YearMonth.parse(month); }
        catch (java.time.format.DateTimeParseException ex) { selectedMonth = YearMonth.now(); }
        LocalDate selectedDate;
        try { selectedDate = date == null ? LocalDate.now() : LocalDate.parse(date); }
        catch (java.time.format.DateTimeParseException ex) { selectedDate = LocalDate.now(); }
        model.addAttribute("calendarView", calendarView);
        model.addAttribute("weekView", weekView);
        model.addAttribute("weekDate", selectedDate);
        model.addAttribute("previousWeek", selectedDate.minusWeeks(1));
        model.addAttribute("nextWeek", selectedDate.plusWeeks(1));
        model.addAttribute("calendarMonth", selectedMonth);
        model.addAttribute("previousMonth", selectedMonth.minusMonths(1));
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1));
        model.addAttribute("calendarWeeks", weekView ? CalendarGrid.week(selectedDate, tasks) : CalendarGrid.month(selectedMonth, tasks));
        model.addAttribute("undatedTasks", tasks.stream().filter(t -> t.getDueDate() == null).toList());
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "开发人员");
        model.addAttribute("tasks", tasks);
        model.addAttribute("reminders", reminders.unread(developerId));
        model.addAttribute("editableTaskIds", tasks.stream().filter(t -> service.activeMember(t.getProject().getId(), developerId)).map(t -> t.getId()).toList());
        model.addAttribute("statuses", TaskStatus.values());
        if (session.getAttribute("myTasksCsrf") == null) session.setAttribute("myTasksCsrf", java.util.UUID.randomUUID().toString());
        model.addAttribute("csrfToken", session.getAttribute("myTasksCsrf"));
        model.addAttribute("todoCount", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.TODO).count());
        model.addAttribute("doingCount", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count());
        model.addAttribute("doneCount", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count());
        return "my-tasks";
    }

    @PostMapping("/my-tasks/{taskId}/reminder/read")
    public String markReminderRead(@PathVariable Long taskId, @RequestParam(defaultValue = "") String csrfToken,
                                   HttpSession session, RedirectAttributes redirect) {
        Long developerId = requireDeveloper(session);
        if (developerId == null) return "redirect:/login";
        try {
            if (csrfToken.isBlank() || !csrfToken.equals(session.getAttribute("myTasksCsrf")))
                throw new BusinessException("页面已失效，请刷新后重新操作");
            reminders.markRead(developerId, taskId);
            redirect.addFlashAttribute("success", "提醒已标记为已读");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/my-tasks";
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
        if (session.getAttribute(SessionKeys.ROLE) == Role.PROJECT_MANAGER) {
            Object userId = session.getAttribute(SessionKeys.USER_ID);
            if (!(userId instanceof Long managerId)) return "redirect:/login";
            var developers = service.developers(managerId);
            model.addAttribute("projects", service.projectsForManager(managerId).stream().map(project -> {
                var members = service.members(managerId, project.getId());
                var memberIds = members.stream().map(member -> member.getUser().getId()).toList();
                return new ManagedProject(project, members, developers.stream()
                        .filter(user -> !memberIds.contains(user.getId())).toList());
            }).toList());
            model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
            model.addAttribute("roleLabel", "项目负责人");
            if (session.getAttribute("projectCsrf") == null)
                session.setAttribute("projectCsrf", java.util.UUID.randomUUID().toString());
            model.addAttribute("projectCsrf", session.getAttribute("projectCsrf"));
            return "manager-projects";
        }
        Long id = requireDeveloper(session);
        if (id == null) return "redirect:/login";
        model.addAttribute("projects", service.projectsForDeveloper(id));
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "开发人员");
        return "member-projects";
    }

    public record ManagedProject(Project project, java.util.List<ProjectMember> members,
                                 java.util.List<User> candidates) {}

    /** 校验当前会话是否属于开发人员。 */
    private Long requireDeveloper(HttpSession session) {
        Object id = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        return id instanceof Long && role == Role.DEVELOPER ? (Long) id : null;
    }
}
