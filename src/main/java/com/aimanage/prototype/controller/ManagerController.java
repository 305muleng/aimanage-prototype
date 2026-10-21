package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Project;
import com.aimanage.prototype.domain.ProjectMember;
import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.domain.MemberStatus;
import com.aimanage.prototype.domain.ProjectStatus;
import com.aimanage.prototype.domain.TaskStatus;
import com.aimanage.prototype.service.CalendarGrid;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.service.ProjectTaskService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 项目负责人工作台控制器。
 *
 * <p>所有写入入口都会先校验会话角色，具体项目归属和成员范围由业务服务再次校验。</p>
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final ProjectTaskService service;

    public ManagerController(ProjectTaskService service) {
        this.service = service;
    }

    @GetMapping
    public String dashboard(HttpSession session, Model model,
                            @RequestParam(defaultValue = "") String keyword,
                            @RequestParam(required = false) ProjectStatus status,
                            @RequestParam(defaultValue = "recent") String sort) {
        Long managerId = requireManager(session);
        if (managerId == null) return "redirect:/login";

        var allProjects = service.projectsForManager(managerId);
        model.addAttribute("shareProjects", allProjects);
        var filteredProjects = allProjects.stream()
                .filter(project -> keyword.isBlank() || project.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains(keyword.trim().toLowerCase(java.util.Locale.ROOT)))
                .filter(project -> status == null || project.getStatus() == status).toList();
        java.util.Comparator<Project> projectOrder = switch (sort) {
            case "name" -> java.util.Comparator.comparing(Project::getName);
            case "status" -> java.util.Comparator.comparing(Project::getStatus).thenComparing(Project::getName);
            default -> java.util.Comparator.comparing(Project::getCreatedAt).reversed();
        };
        List<ProjectView> projectViews = filteredProjects.stream().sorted(projectOrder)
                .map(project -> {
                    var members = service.members(managerId, project.getId());
                    return new ProjectView(project, members, service.projectTasks(managerId, project.getId()), service.stories(managerId,project.getId()),
                            members.stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).toList(),
                            service.projectStatusHistory(managerId, project.getId()));
                })
                .toList();

        long taskCount = service.taskCount(managerId);
        long doneCount = service.doneTaskCount(managerId);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "项目负责人");
        model.addAttribute("projects", projectViews);
        model.addAttribute("developers", service.developers(managerId));
        model.addAttribute("projectCount", allProjects.size());
        model.addAttribute("filteredCount", projectViews.size());
        model.addAttribute("projectKeyword", keyword.trim());
        model.addAttribute("selectedProjectStatus", status);
        model.addAttribute("projectSort", sort);
        model.addAttribute("projectStatuses", ProjectStatus.values());
        model.addAttribute("taskCount", taskCount);
        model.addAttribute("doneCount", doneCount);
        model.addAttribute("progress", taskCount == 0 ? 0 : Math.round(doneCount * 100.0 / taskCount));
        model.addAttribute("memberStatuses", MemberStatus.values());
        if (session.getAttribute("projectCsrf") == null) session.setAttribute("projectCsrf", java.util.UUID.randomUUID().toString());
        model.addAttribute("projectCsrf", session.getAttribute("projectCsrf"));
        return "manager-dashboard";
    }

    @GetMapping("/calendar")
    public String calendar(HttpSession session, Model model,
                           RedirectAttributes redirect,
                           @RequestParam(required = false) Long projectId,
                           @RequestParam(defaultValue = "month") String view,
                           @RequestParam(required = false) String month,
                           @RequestParam(required = false) String date) {
        Long managerId = requireManager(session);
        if (managerId == null) return "redirect:/login";
        List<Project> projects = service.projectsForManager(managerId);
        if (projectId != null && projects.stream().noneMatch(project -> project.getId().equals(projectId))) {
            redirect.addFlashAttribute("error", "无权查看该项目的日历");
            return "redirect:/manager";
        }
        List<Task> tasks = projects.stream().filter(project -> projectId == null || project.getId().equals(projectId))
                .flatMap(project -> service.projectTasks(managerId, project.getId()).stream()).toList();
        YearMonth selectedMonth;
        try { selectedMonth = month == null ? YearMonth.now() : YearMonth.parse(month); }
        catch (java.time.format.DateTimeParseException ex) { selectedMonth = YearMonth.now(); }
        LocalDate selectedDate;
        try { selectedDate = date == null ? LocalDate.now() : LocalDate.parse(date); }
        catch (java.time.format.DateTimeParseException ex) { selectedDate = LocalDate.now(); }
        boolean weekView = "week".equals(view);
        model.addAttribute("projects", projects);
        model.addAttribute("selectedProjectId", projectId);
        model.addAttribute("tasks", tasks);
        model.addAttribute("editableTasks", tasks.stream().filter(task -> task.getStatus() != TaskStatus.DONE
                && task.getProject().getStatus() != ProjectStatus.PAUSED
                && task.getProject().getStatus() != ProjectStatus.COMPLETED).toList());
        model.addAttribute("calendarWeeks", weekView ? CalendarGrid.week(selectedDate, tasks) : CalendarGrid.month(selectedMonth, tasks));
        model.addAttribute("undatedTasks", tasks.stream().filter(task -> task.getDueDate() == null).toList());
        model.addAttribute("weekView", weekView);
        model.addAttribute("calendarMonth", selectedMonth);
        model.addAttribute("previousMonth", selectedMonth.minusMonths(1));
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1));
        model.addAttribute("weekDate", selectedDate);
        model.addAttribute("previousWeek", selectedDate.minusWeeks(1));
        model.addAttribute("nextWeek", selectedDate.plusWeeks(1));
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "项目负责人");
        if (session.getAttribute("projectCsrf") == null)
            session.setAttribute("projectCsrf", java.util.UUID.randomUUID().toString());
        model.addAttribute("projectCsrf", session.getAttribute("projectCsrf"));
        return "manager-calendar";
    }

    @PostMapping("/calendar/reschedule")
    public String reschedule(@RequestParam Long taskId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                             @RequestParam Long version,
                             @RequestParam(defaultValue = "") String csrfToken,
                             @RequestParam(required = false) Long projectId,
                             @RequestParam(defaultValue = "month") String view,
                             @RequestParam(required = false) String month,
                             @RequestParam(required = false) String date,
                             HttpSession session, RedirectAttributes redirect) {
        Long managerId = requireManager(session);
        if (managerId == null) return "redirect:/login";
        try {
            checkToken(session, csrfToken);
            service.rescheduleTask(managerId, taskId, dueDate, version);
            redirect.addFlashAttribute("success", "任务截止日期已更新");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        if (projectId != null) redirect.addAttribute("projectId", projectId);
        redirect.addAttribute("view", "week".equals(view) ? "week" : "month");
        if (month != null) redirect.addAttribute("month", month);
        if (date != null) redirect.addAttribute("date", date);
        return "redirect:/manager/calendar";
    }

    @PostMapping("/projects")
    public String createProject(@RequestParam String name, @RequestParam(required = false) String description,
                                @RequestParam(defaultValue = "") String csrfToken,
                                HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.createProject((Long) session.getAttribute(SessionKeys.USER_ID), name, description);
            redirect.addFlashAttribute("success", "项目创建成功，可以继续添加成员和任务");
        });
    }

    @PostMapping("/projects/{projectId}/edit")
    public String editProject(@PathVariable Long projectId, @RequestParam(defaultValue = "") String name,
                              @RequestParam(defaultValue = "") String description, @RequestParam(defaultValue = "") String csrfToken,
                              HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.editProject((Long) session.getAttribute(SessionKeys.USER_ID), projectId, name, description);
            redirect.addFlashAttribute("success", "项目名称和简介已更新");
        });
    }

    @PostMapping("/projects/{projectId}/status")
    public String changeProjectStatus(@PathVariable Long projectId, @RequestParam ProjectStatus status,
                                      @RequestParam(defaultValue = "") String reason,
                                      @RequestParam(defaultValue = "") String csrfToken,
                                      HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.changeProjectStatus((Long) session.getAttribute(SessionKeys.USER_ID), projectId, status, reason);
            redirect.addFlashAttribute("success", "项目状态已更新");
        });
    }

    @PostMapping("/projects/{projectId}/members/{userId}/edit")
    public String editMember(@PathVariable Long projectId, @PathVariable Long userId,
                             @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String projectIdentity,
                             @RequestParam(defaultValue = "") String responsibilities, @RequestParam(defaultValue = "") String csrfToken,
                             @RequestParam(defaultValue = "false") boolean returnToProjects,
                             HttpSession session, RedirectAttributes redirect) {
        String result = execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            MemberStatus parsed;
            try { parsed = MemberStatus.valueOf(status); }
            catch (IllegalArgumentException ex) { throw new BusinessException("请选择有效的成员状态"); }
            service.updateMember((Long)session.getAttribute(SessionKeys.USER_ID), projectId, userId, parsed, projectIdentity, responsibilities);
            redirect.addFlashAttribute("success", "成员状态、身份和职责已保存");
        });
        return returnToProjects && result.equals("redirect:/manager") ? "redirect:/projects" : result;
    }

    @PostMapping("/projects/{projectId}/members/{userId}/remove")
    public String removeMember(@PathVariable Long projectId, @PathVariable Long userId, @RequestParam(defaultValue = "") String csrfToken,
                               @RequestParam(defaultValue = "false") boolean returnToProjects,
                               HttpSession session, RedirectAttributes redirect) {
        String result = execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.removeMember((Long)session.getAttribute(SessionKeys.USER_ID), projectId, userId);
            redirect.addFlashAttribute("success", "成员已移出项目，其系统账号仍保留");
        });
        return returnToProjects && result.equals("redirect:/manager") ? "redirect:/projects" : result;
    }

    private void checkToken(HttpSession session, String token) {
        if (token.isBlank() || !token.equals(session.getAttribute("projectCsrf"))) throw new BusinessException("页面已失效，请刷新后重新操作");
    }

    @PostMapping("/projects/{projectId}/members")
    public String addMember(@PathVariable Long projectId, @RequestParam Long userId,
                            @RequestParam(defaultValue = "") String csrfToken,
                            @RequestParam(defaultValue = "false") boolean returnToProjects,
                            HttpSession session, RedirectAttributes redirect) {
        String result = execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.addMember((Long) session.getAttribute(SessionKeys.USER_ID), projectId, userId);
            redirect.addFlashAttribute("success", "成员已加入项目");
        });
        return returnToProjects && result.equals("redirect:/manager") ? "redirect:/projects" : result;
    }

    @PostMapping("/projects/{projectId}/tasks")
    public String createTask(@PathVariable Long projectId, @RequestParam String title,
                             @RequestParam(required = false) String description,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                             @RequestParam Long assigneeId, @RequestParam(defaultValue = "") String csrfToken,
                             HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.createTask((Long) session.getAttribute(SessionKeys.USER_ID), projectId,
                    title, description, dueDate, assigneeId);
            redirect.addFlashAttribute("success", "任务已创建并分配");
        });
    }

    @PostMapping("/tasks/{taskId}/edit")
    public String editTask(@PathVariable Long taskId, @RequestParam String title,
                           @RequestParam(required = false) String description,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                           @RequestParam Long assigneeId, @RequestParam(defaultValue = "") String csrfToken,
                           @RequestParam(required = false) Long version, HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.editTask((Long) session.getAttribute(SessionKeys.USER_ID), taskId,
                    title, description, dueDate, assigneeId, version);
            redirect.addFlashAttribute("success", "任务修改成功");
        });
    }

    /** 统一执行负责人写操作，将业务校验结果以页面消息反馈给用户。 */
    private String execute(HttpSession session, RedirectAttributes redirect, Runnable action) {
        if (requireManager(session) == null) return "redirect:/login";
        try {
            action.run();
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /** 检查当前会话是否属于项目负责人。 */
    private Long requireManager(HttpSession session) {
        Object id = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        return id instanceof Long && role == Role.PROJECT_MANAGER ? (Long) id : null;
    }

    @PostMapping("/tasks/{taskId}/plan")
    public String plan(@PathVariable Long taskId,@RequestParam com.aimanage.prototype.domain.Priority priority,
                       @RequestParam(required=false) Long storyId,@RequestParam(defaultValue="") String csrfToken,
                       @RequestParam(required=false) Long version, HttpSession session,RedirectAttributes redirect) {
        return execute(session,redirect,()->{checkToken(session,csrfToken);service.planTask((Long)session.getAttribute(SessionKeys.USER_ID),taskId,priority,storyId,version);redirect.addFlashAttribute("success","任务优先级和用户故事已保存");});
    }
    public record ProjectView(Project project, List<ProjectMember> members, List<Task> tasks, List<com.aimanage.prototype.domain.WorkItem> stories, List<ProjectMember> activeMembers,
                              List<com.aimanage.prototype.domain.ProjectStatusChange> statusHistory) {
    }
}
