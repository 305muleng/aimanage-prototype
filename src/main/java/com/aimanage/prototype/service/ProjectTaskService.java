package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint 1 的核心业务服务，集中实现项目、成员与任务的业务规则。
 *
 * <p>所有写操作都在事务中执行，避免控制器绕过项目归属、成员范围和任务负责人校验。</p>
 */
@Service
@Transactional
public class ProjectTaskService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final TaskRepository taskRepository;
    private final WorkItemRepository workItems;

    public ProjectTaskService(ProjectRepository projectRepository,
                              UserRepository userRepository,
                              ProjectMemberRepository memberRepository,
                              TaskRepository taskRepository, WorkItemRepository workItems) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
        this.workItems = workItems;
    }

    /** 创建项目；只有项目负责人角色可以执行。 */
    public Project createProject(Long managerId, String name, String description) {
        User manager = requireUser(managerId);
        requireRole(manager, Role.PROJECT_MANAGER, "只有项目负责人可以创建项目");
        requireText(name, "项目名称不能为空");
        validateProject(name, description);
        return projectRepository.save(new Project(name.trim(), cleanDescription(description), manager));
    }

    /** 将开发人员加入负责人自己的项目，并阻止重复加入。 */
    public ProjectMember addMember(Long managerId, Long projectId, Long userId) {
        Project project = requireOwnedProject(managerId, projectId);
        User member = requireUser(userId);
        requireRole(member, Role.DEVELOPER, "只能添加开发人员为项目成员");
        if (memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(member.getDisplayName() + " 已经在项目中，不能重复添加");
        }
        return memberRepository.save(new ProjectMember(project, member));
    }

    /** 创建任务；负责人必须是当前项目成员。 */
    public Task createTask(Long managerId, Long projectId, String title, String description,
                           LocalDate dueDate, Long assigneeId) {
        Project project = requireOwnedProject(managerId, projectId);
        User assignee = requireProjectMember(projectId, assigneeId);
        requireText(title, "任务标题不能为空");
        return taskRepository.save(new Task(title.trim(), cleanDescription(description), dueDate, project, assignee));
    }

    /** 编辑任务内容和负责人，同时再次执行项目归属及成员范围校验。 */
    public Task editTask(Long managerId, Long taskId, String title, String description,
                         LocalDate dueDate, Long assigneeId) {
        Task task = requireTask(taskId);
        requireOwnedProject(managerId, task.getProject().getId());
        User assignee = requireProjectMember(task.getProject().getId(), assigneeId);
        requireText(title, "任务标题不能为空");
        task.edit(title.trim(), cleanDescription(description), dueDate, assignee);
        return task;
    }

    /**
     * 更新任务状态；只有当前任务负责人本人可以操作，防止跨用户越权。
     */
    public Task updateStatus(Long developerId, Long taskId, TaskStatus status) {
        requireRole(requireUser(developerId), Role.DEVELOPER, "只有开发人员可以更新本人任务");
        Task task = requireTask(taskId);
        if (!task.getAssignee().getId().equals(developerId)) {
            throw new BusinessException("只能更新分配给自己的任务");
        }
        if (status == null) {
            throw new BusinessException("请选择任务状态");
        }
        requireProjectMember(task.getProject().getId(), developerId);
        task.changeStatus(status);
        return task;
    }

    @Transactional(readOnly = true)
    public List<Project> projectsForManager(Long managerId) {
        requireRole(requireUser(managerId), Role.PROJECT_MANAGER, "只有项目负责人可以查看负责的项目");
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(managerId);
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> members(Long userId, Long projectId) {
        requireVisibleProject(userId, projectId);
        return memberRepository.findByProjectIdOrderByUserDisplayName(projectId);
    }

    @Transactional(readOnly = true)
    public List<Task> projectTasks(Long managerId, Long projectId) {
        requireOwnedProject(managerId, projectId);
        return taskRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    /** 查询分配给指定开发人员的任务，用于“我的任务”页面的数据隔离。 */
    @Transactional(readOnly = true)
    public List<Task> myTasks(Long developerId) {
        requireRole(requireUser(developerId), Role.DEVELOPER, "只有开发人员可以查看本人任务");
        return taskRepository.findByAssigneeIdOrderByUpdatedAtDesc(developerId).stream()
                .filter(task -> memberRepository.existsByProjectIdAndUserId(task.getProject().getId(), developerId)).toList();
    }

    @Transactional(readOnly = true)
    public List<User> developers(Long managerId) {
        requireRole(requireUser(managerId), Role.PROJECT_MANAGER, "只有项目负责人可以查询候选成员");
        return userRepository.findByRoleOrderByDisplayName(Role.DEVELOPER);
    }

    @Transactional(readOnly = true)
    public long taskCount(Long managerId) {
        requireRole(requireUser(managerId), Role.PROJECT_MANAGER, "只有项目负责人可以查看项目统计");
        return taskRepository.countByProjectOwnerId(managerId);
    }

    @Transactional(readOnly = true)
    public long doneTaskCount(Long managerId) {
        requireRole(requireUser(managerId), Role.PROJECT_MANAGER, "只有项目负责人可以查看项目统计");
        return taskRepository.countByProjectOwnerIdAndStatus(managerId, TaskStatus.DONE);
    }

    /** 校验项目存在且由当前项目负责人创建。 */
    private Project requireOwnedProject(Long managerId, Long projectId) {
        requireRole(requireUser(managerId), Role.PROJECT_MANAGER, "只有项目负责人可以管理项目");
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException("项目不存在"));
        if (!project.getOwner().getId().equals(managerId)) {
            throw new BusinessException("无权操作其他负责人创建的项目");
        }
        return project;
    }

    /** 校验候选任务负责人已加入当前项目。 */
    private User requireProjectMember(Long projectId, Long userId) {
        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException("任务负责人必须是该项目成员"));
        if (member.getStatus() != MemberStatus.ACTIVE) throw new BusinessException("成员已暂停参与，请先恢复成员状态或转交任务");
        User user = requireUser(userId);
        requireRole(user, Role.DEVELOPER, "任务负责人必须具有开发人员权限");
        return user;
    }

    public Project editProject(Long managerId, Long projectId, String name, String description) {
        Project project = requireOwnedProject(managerId, projectId);
        validateProject(name, description);
        project.edit(name.trim(), cleanDescription(description));
        return project;
    }

    public void updateMember(Long managerId, Long projectId, Long userId, MemberStatus status,
                             String projectIdentity, String responsibilities) {
        requireOwnedProject(managerId, projectId);
        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException("该成员不在当前项目中"));
        if (status == null) throw new BusinessException("请选择成员状态");
        String identity = projectIdentity == null ? "" : projectIdentity.trim();
        String duties = responsibilities == null ? "" : responsibilities.trim();
        if (identity.isBlank() || identity.length() > 50) throw new BusinessException("项目身份需为 1–50 个字符");
        if (duties.length() > 500) throw new BusinessException("职责说明最多 500 个字符");
        member.updateDetails(status, identity, duties);
    }

    public void removeMember(Long managerId, Long projectId, Long userId) {
        requireOwnedProject(managerId, projectId);
        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException("该成员不在当前项目中"));
        if (taskRepository.existsByProjectIdAndAssigneeId(projectId, userId)) {
            throw new BusinessException("该成员仍有任务，请先将其全部任务转交其他正常参与的成员，再移除");
        }
        memberRepository.delete(member);
    }

    @Transactional(readOnly = true)
    public List<MemberProjectView> projectsForDeveloper(Long userId) {
        requireRole(requireUser(userId), Role.DEVELOPER, "只有开发人员可以查看参与的项目");
        return memberRepository.findByUserId(userId).stream().map(own ->
                new MemberProjectView(own.getProject(), own, members(userId, own.getProject().getId()))).toList();
    }

    @Transactional(readOnly = true)
    public boolean activeMember(Long projectId, Long userId) {
        requireRole(requireUser(userId), Role.DEVELOPER, "只有开发人员可以查询本人参与状态");
        return memberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getStatus() == MemberStatus.ACTIVE).orElse(false);
    }

    private void validateProject(String name, String description) {
        requireText(name, "项目名称不能为空");
        if (name.trim().length() > 100) throw new BusinessException("项目名称最多 100 个字符");
        if (description != null && description.trim().length() > 500) throw new BusinessException("项目简介最多 500 个字符");
    }

    private void requireVisibleProject(Long userId, Long projectId) {
        User user = requireUser(userId);
        if (user.getRole() == Role.PROJECT_MANAGER) {
            requireOwnedProject(userId, projectId);
        } else if (user.getRole() != Role.DEVELOPER || !memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException("无权查看该项目的成员资料");
        }
    }

    public record MemberProjectView(Project project, ProjectMember own, List<ProjectMember> members) {}
    public void planTask(Long managerId,Long taskId,Priority priority,Long storyId) {
        Task task=requireTask(taskId);requireOwnedProject(managerId,task.getProject().getId());
        if(priority==null)throw new BusinessException("请选择优先级");
        WorkItem story=null;
        if(storyId!=null) {
            story=workItems.findById(storyId).orElseThrow(()->new BusinessException("用户故事不存在"));
            if(!story.getType().equals("STORY")||!story.getProject().getId().equals(task.getProject().getId()))throw new BusinessException("只能关联当前项目的用户故事");
        }
        task.plan(priority,story);
    }
    public void scheduleTask(Long managerId,Long taskId,LocalDate start,LocalDate end){
        var task=requireTask(taskId);requireOwnedProject(managerId,task.getProject().getId());
        if(start==null||end==null||end.isBefore(start))throw new BusinessException("请填写有效起止日期，结束不能早于开始");
        task.schedule(start,end);
    }
    @Transactional(readOnly=true)
    public List<WorkItem> stories(Long managerId,Long projectId) {
        requireOwnedProject(managerId,projectId);
        return workItems.findByProjectIdOrderByIdDesc(projectId).stream().filter(i->i.getType().equals("STORY")).toList();
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
