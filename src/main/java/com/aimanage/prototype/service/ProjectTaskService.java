package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class ProjectTaskService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final TaskRepository taskRepository;

    public ProjectTaskService(ProjectRepository projectRepository,
                              UserRepository userRepository,
                              ProjectMemberRepository memberRepository,
                              TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
    }

    public Project createProject(Long managerId, String name, String description) {
        User manager = requireUser(managerId);
        requireRole(manager, Role.PROJECT_MANAGER, "只有项目负责人可以创建项目");
        requireText(name, "项目名称不能为空");
        return projectRepository.save(new Project(name.trim(), cleanDescription(description), manager));
    }

    public ProjectMember addMember(Long managerId, Long projectId, Long userId) {
        Project project = requireOwnedProject(managerId, projectId);
        User member = requireUser(userId);
        requireRole(member, Role.DEVELOPER, "只能添加开发人员为项目成员");
        if (memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(member.getDisplayName() + " 已经在项目中，不能重复添加");
        }
        return memberRepository.save(new ProjectMember(project, member));
    }

    public Task createTask(Long managerId, Long projectId, String title, String description,
                           LocalDate dueDate, Long assigneeId) {
        Project project = requireOwnedProject(managerId, projectId);
        User assignee = requireProjectMember(projectId, assigneeId);
        requireText(title, "任务标题不能为空");
        return taskRepository.save(new Task(title.trim(), cleanDescription(description), dueDate, project, assignee));
    }

    public Task editTask(Long managerId, Long taskId, String title, String description,
                         LocalDate dueDate, Long assigneeId) {
        Task task = requireTask(taskId);
        requireOwnedProject(managerId, task.getProject().getId());
        User assignee = requireProjectMember(task.getProject().getId(), assigneeId);
        requireText(title, "任务标题不能为空");
        task.edit(title.trim(), cleanDescription(description), dueDate, assignee);
        return task;
    }

    public Task updateStatus(Long developerId, Long taskId, TaskStatus status) {
        Task task = requireTask(taskId);
        if (!task.getAssignee().getId().equals(developerId)) {
            throw new BusinessException("只能更新分配给自己的任务");
        }
        if (status == null) {
            throw new BusinessException("请选择任务状态");
        }
        task.changeStatus(status);
        return task;
    }

    @Transactional(readOnly = true)
    public List<Project> projectsForManager(Long managerId) {
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(managerId);
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> members(Long projectId) {
        return memberRepository.findByProjectIdOrderByUserDisplayName(projectId);
    }

    @Transactional(readOnly = true)
    public List<Task> projectTasks(Long projectId) {
        return taskRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<Task> myTasks(Long developerId) {
        return taskRepository.findByAssigneeIdOrderByUpdatedAtDesc(developerId);
    }

    @Transactional(readOnly = true)
    public List<User> developers() {
        return userRepository.findByRoleOrderByDisplayName(Role.DEVELOPER);
    }

    @Transactional(readOnly = true)
    public long taskCount(Long managerId) {
        return taskRepository.countByProjectOwnerId(managerId);
    }

    @Transactional(readOnly = true)
    public long doneTaskCount(Long managerId) {
        return taskRepository.countByProjectOwnerIdAndStatus(managerId, TaskStatus.DONE);
    }

    private Project requireOwnedProject(Long managerId, Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException("项目不存在"));
        if (!project.getOwner().getId().equals(managerId)) {
            throw new BusinessException("无权操作其他负责人创建的项目");
        }
        return project;
    }

    private User requireProjectMember(Long projectId, Long userId) {
        if (!memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException("任务负责人必须是该项目成员");
        }
        return requireUser(userId);
    }

    private User requireUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BusinessException("用户不存在"));
    }

    private Task requireTask(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new BusinessException("任务不存在"));
    }

    private void requireRole(User user, Role role, String message) {
        if (user.getRole() != role) {
            throw new BusinessException(message);
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(message);
        }
    }

    private String cleanDescription(String value) {
        return value == null || value.isBlank() ? "暂无描述" : value.trim();
    }
}
