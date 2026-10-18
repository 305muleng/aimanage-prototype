package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Project;
import com.aimanage.prototype.domain.ProjectMember;
import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.domain.MemberStatus;
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
    public String dashboard(HttpSession session, Model model) {
        Long managerId = requireManager(session);
        if (managerId == null) return "redirect:/login";

        List<ProjectView> projectViews = service.projectsForManager(managerId).stream()
                .map(project -> {
                    var members = service.members(managerId, project.getId());
                    return new ProjectView(project, members, service.projectTasks(managerId, project.getId()), service.stories(managerId,project.getId()),
                            members.stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).toList());
                })
                .toList();

        long taskCount = service.taskCount(managerId);
        long doneCount = service.doneTaskCount(managerId);
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        model.addAttribute("roleLabel", "项目负责人");
        model.addAttribute("projects", projectViews);
        model.addAttribute("developers", service.developers(managerId));
        model.addAttribute("projectCount", projectViews.size());
        model.addAttribute("taskCount", taskCount);
        model.addAttribute("doneCount", doneCount);
        model.addAttribute("progress", taskCount == 0 ? 0 : Math.round(doneCount * 100.0 / taskCount));
        model.addAttribute("memberStatuses", MemberStatus.values());
        if (session.getAttribute("projectCsrf") == null) session.setAttribute("projectCsrf", java.util.UUID.randomUUID().toString());
        model.addAttribute("projectCsrf", session.getAttribute("projectCsrf"));
        return "manager-dashboard";
    }

    @GetMapping("/tasks")
    public String tasks(@RequestParam(required=false) Long projectId,@RequestParam(required=false) Long storyId,HttpSession session,Model model){
        String result=dashboard(session,model);if(result.startsWith("redirect:"))return result;
        @SuppressWarnings("unchecked") var views=(List<ProjectView>)model.getAttribute("projects");
        var view=views.stream().filter(v->projectId==null || v.project().getId().equals(projectId)).findFirst().orElse(null);
        model.addAttribute("view",view);
        model.addAttribute("selectedStory",view!=null && storyId!=null && view.stories().stream().anyMatch(i->i.getId().equals(storyId))?storyId:null);
        return "task-workspace";
    }
    @PostMapping("/tasks/save")
    public String saveTask(@RequestParam Long projectId,@RequestParam(required=false) Long id,@RequestParam String title,
        @RequestParam(defaultValue="") String description,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dueDate,
        @RequestParam Long assigneeId,@RequestParam com.aimanage.prototype.domain.Priority priority,@RequestParam(required=false) Long storyId,
        @RequestParam(defaultValue="") String csrfToken,HttpSession session,RedirectAttributes redirect){
        if(requireManager(session)==null)return "redirect:/login";
        try{checkToken(session,csrfToken);service.savePlannedTask(requireManager(session),projectId,id,title,description,dueDate,assigneeId,priority,storyId);redirect.addFlashAttribute("success","任务已保存");}
        catch(BusinessException ex){redirect.addFlashAttribute("error",ex.getMessage());}
        return "redirect:/manager/tasks?projectId="+projectId;
    }

    @PostMapping("/projects")
    public String createProject(@RequestParam String name, @RequestParam(required = false) String description,
                                HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
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

    @PostMapping("/projects/{projectId}/members/{userId}/edit")
    public String editMember(@PathVariable Long projectId, @PathVariable Long userId,
                             @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String projectIdentity,
                             @RequestParam(defaultValue = "") String responsibilities, @RequestParam(defaultValue = "") String csrfToken,
                             HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            MemberStatus parsed;
            try { parsed = MemberStatus.valueOf(status); }
            catch (IllegalArgumentException ex) { throw new BusinessException("请选择有效的成员状态"); }
            service.updateMember((Long)session.getAttribute(SessionKeys.USER_ID), projectId, userId, parsed, projectIdentity, responsibilities);
            redirect.addFlashAttribute("success", "成员状态、身份和职责已保存");
        });
    }

    @PostMapping("/projects/{projectId}/members/{userId}/remove")
    public String removeMember(@PathVariable Long projectId, @PathVariable Long userId, @RequestParam(defaultValue = "") String csrfToken,
                               HttpSession session, RedirectAttributes redirect) {
        return execute(session, redirect, () -> {
            checkToken(session, csrfToken);
            service.removeMember((Long)session.getAttribute(SessionKeys.USER_ID), projectId, userId);
            redirect.addFlashAttribute("success", "成员已移出项目，其系统账号仍保留");
        });
    }

    private void checkToken(HttpSession session, String token) {
        if (token.isBlank() || !token.equals(session.getAttribute("projectCsrf"))) throw new BusinessException("页面已失效，请刷新后重新操作");
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
                       HttpSession session,RedirectAttributes redirect) {
        return execute(session,redirect,()->{checkToken(session,csrfToken);service.planTask((Long)session.getAttribute(SessionKeys.USER_ID),taskId,priority,storyId);redirect.addFlashAttribute("success","任务优先级和用户故事已保存");});
    }
    public record ProjectView(Project project, List<ProjectMember> members, List<Task> tasks, List<com.aimanage.prototype.domain.WorkItem> stories, List<ProjectMember> activeMembers) {
    }
}
