package com.aimanage.prototype.controller;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.repository.ProjectMemberRepository;
import com.aimanage.prototype.repository.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Displays an actual accessible project in the shared navigation header. */
@ControllerAdvice
public class WorkspaceModelAdvice {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    public WorkspaceModelAdvice(ProjectRepository projects, ProjectMemberRepository members) {
        this.projects = projects;
        this.members = members;
    }

    @ModelAttribute("workspaceName")
    public String workspaceName(HttpSession session, HttpServletRequest request) {
        Object identity = session.getAttribute(SessionKeys.USER_ID);
        Object role = session.getAttribute(SessionKeys.ROLE);
        if (!(identity instanceof Long userId)) return "项目空间";
        if (role == Role.SYSTEM_ADMIN) return "系统管理";
        String selected = request.getParameter("projectId");
        if (selected != null) {
            try {
                long projectId = Long.parseLong(selected);
                var project = projects.findById(projectId);
                if (project.isPresent() && (role == Role.PROJECT_MANAGER
                        && project.get().getOwner().getId().equals(userId)
                        || role == Role.DEVELOPER && members.existsByProjectIdAndUserId(projectId, userId)))
                    return project.get().getName();
            } catch (NumberFormatException ignored) { /* Invalid query does not select a workspace. */ }
        }
        if (role == Role.PROJECT_MANAGER)
            return projects.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(project -> project.getName()).orElse("我的项目空间");
        if (role == Role.DEVELOPER)
            return members.findByUserId(userId).stream()
                    .findFirst().map(member -> member.getProject().getName()).orElse("我的项目空间");
        return "项目空间";
    }
}
