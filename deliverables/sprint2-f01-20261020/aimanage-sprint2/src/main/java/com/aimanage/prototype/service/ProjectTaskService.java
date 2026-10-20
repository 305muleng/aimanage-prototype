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
    private final TaskJournal journal;
    private final TaskDependencyRepository dependencies;

    public ProjectTaskService(ProjectRepository projectRepository,
                              UserRepository userRepository,
                              ProjectMemberRepository memberRepository,
                              TaskRepository taskRepository, WorkItemRepository workItems, TaskJournal journal,
                              TaskDependencyRepository dependencies) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
        this.workItems = workItems;
        this.journal = journal;
        this.dependencies = dependencies;
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
        validateTask(title, description);
        Task task = taskRepository.save(new Task(title.trim(), cleanDescription(description), dueDate, project, assignee));
        journal.record(task, managerId, "创建任务并分配给：" + assignee.getDisplayName());
        return task;
    }

    /** 编辑任务内容和负责人，同时再次执行项目归属及成员范围校验。 */
    public Task editTask(Long managerId, Long taskId, String title, String description,
                         LocalDate dueDate, Long assigneeId) {
        Task task = requireTask(taskId);
        requireOwnedProject(managerId, task.getProject().getId());
        if (task.getStatus() == TaskStatus.DONE && (!task.getTitle().equals(title == null ? "" : title.trim())
                || !task.getDescription().equals(cleanDescription(description))
                || !task.getAssignee().getId().equals(assigneeId)))
            throw new BusinessException("已完成任务修改工作内容或负责人前，请先填写原因并重开");
        User assignee = task.getStatus() == TaskStatus.DONE ? task.getAssignee()
                : requireProjectMember(task.getProject().getId(), assigneeId);
        validateTask(title, description);
        String oldTitle = task.getTitle();
        String oldDescription = task.getDescription();
        LocalDate oldDueDate = task.getDueDate();
        Long oldAssigneeId = task.getAssignee().getId();
        String oldAssigneeName = task.getAssignee().getDisplayName();
        task.edit(title.trim(), cleanDescription(description), dueDate, assignee);
        if (!oldTitle.equals(task.getTitle())) journal.record(task, managerId, "标题：" + oldTitle + " → " + task.getTitle());
        if (!oldDescription.equals(task.getDescription())) journal.record(task, managerId, "修改任务描述");
        if (!java.util.Objects.equals(oldDueDate, dueDate)) journal.record(task, managerId, "截止日期：" + oldDueDate + " → " + dueDate);
        if (!oldAssigneeId.equals(assigneeId)) journal.record(task, managerId, "负责人：" + oldAssigneeName + " → " + assignee.getDisplayName());
        return task;
    }

    /**
     * 更新任务状态；只有当前任务负责人本人可以操作，防止跨用户越权。
     */
    public Task updateStatus(Long developerId, Long taskId, TaskStatus status) {
        return updateStatus(developerId, taskId, status, "");
    }

    /** 完成需要说明，重开需要原因；项目负责人只可重开，不能代替开发人员宣告完成。 */
    public Task updateStatus(Long developerId, Long taskId, TaskStatus status, String note) {
        User actor = requireUser(developerId);
        Task task = requireTask(taskId);
        boolean ownerReopen = actor.getRole() == Role.PROJECT_MANAGER
                && task.getProject().getOwner().getId().equals(developerId)
                && task.getStatus() == TaskStatus.DONE && status != null && status != TaskStatus.DONE;
        if (!ownerReopen && actor.getRole() != Role.DEVELOPER)
            throw new BusinessException("只有开发人员可以更新本人任务，项目负责人仅可重开已完成任务");
        if (!ownerReopen && !task.getAssignee().getId().equals(developerId)) {
            throw new BusinessException("只能更新分配给自己的任务");
        }
        if (status == null) {
            throw new BusinessException("请选择任务状态");
        }
        if (!ownerReopen) requireProjectMember(task.getProject().getId(), developerId);
        TaskStatus oldStatus = task.getStatus();
        if (oldStatus == status) return task;
        String text = note == null ? "" : note.trim();
        if (status == TaskStatus.DONE || oldStatus == TaskStatus.DONE) {
            if (text.isBlank() || text.length() > 1000)
                throw new BusinessException(status == TaskStatus.DONE ? "完成说明需为 1–1000 个字符" : "重开原因需为 1–1000 个字符");
        }
        if (status == TaskStatus.DONE) {
            if (task.isBlocked()) throw new BusinessException("请先解除阻塞，再完成任务");
            boolean unfinished = dependencies.findByTaskProjectId(task.getProject().getId()).stream()
                    .anyMatch(e -> e.getTask().getId().equals(taskId) && e.getPredecessor().getStatus() != TaskStatus.DONE);
            if (unfinished) throw new BusinessException("前置任务尚未完成，不能完成当前任务");
            task.complete(text);
        } else if (oldStatus == TaskStatus.DONE) task.reopen(status);
        else task.changeStatus(status);
        journal.record(task, developerId, "状态：" + oldStatus.getLabel() + " → " + status.getLabel()
                + (status == TaskStatus.DONE ? "；完成说明：" + text : oldStatus == TaskStatus.DONE ? "；重开原因：" + text : ""));
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
        if (status == MemberStatus.PAUSED && taskRepository.existsByProjectIdAndAssigneeIdAndStatusNot(projectId, userId, TaskStatus.DONE))
            throw new BusinessException("该成员仍有未完成任务，请先转交，再暂停参与");
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
        if (taskRepository.existsByProjectIdAndAssigneeIdAndStatusNot(projectId, userId, TaskStatus.DONE)) {
            throw new BusinessException("该成员仍有未完成任务，请先转交，再移除；已完成任务保留历史负责人");
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
        Priority oldPriority = task.getPriority();
        Long oldStoryId = task.getStory() == null ? null : task.getStory().getId();
        task.plan(priority,story);
        if (oldPriority != priority) journal.record(task, managerId, "优先级：" + oldPriority + " → " + priority);
        if (!java.util.Objects.equals(oldStoryId, storyId))
            journal.record(task, managerId, story == null ? "解除任务的用户故事关联" : "关联用户故事：" + story.getTitle());
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

    private void validateTask(String title, String description) {
        requireText(title, "任务标题不能为空");
        if (title.trim().length() > 120) throw new BusinessException("任务标题最多 120 个字符");
        if (description != null && description.trim().length() > 1000)
            throw new BusinessException("任务描述最多 1000 个字符");
    }

    /** 在同一事务内比较页面版本；数据库版本锁继续保护真正同时发生的写入。 */
    public static void requireVersion(Task task, Long expected) {
        if (expected == null || task.getVersion() != expected)
            throw new BusinessException("任务已被更新或页面已失效，请刷新后重新操作");
    }

    public Task editTask(Long managerId, Long taskId, String title, String description,
                         LocalDate dueDate, Long assigneeId, Long version) {
        Task task = requireTask(taskId);
        requireOwnedProject(managerId, task.getProject().getId());
        requireVersion(task, version);
        return editTask(managerId, taskId, title, description, dueDate, assigneeId);
    }

    public Task updateStatus(Long developerId, Long taskId, TaskStatus status, Long version) {
        return updateStatus(developerId, taskId, status, version, "");
    }

    public Task updateStatus(Long developerId, Long taskId, TaskStatus status, Long version, String note) {
        Task task = requireTask(taskId);
        requireVersion(task, version);
        return updateStatus(developerId, taskId, status, note);
    }

    public void planTask(Long managerId, Long taskId, Priority priority, Long storyId, Long version) {
        Task task = requireTask(taskId);
        requireOwnedProject(managerId, task.getProject().getId());
        requireVersion(task, version);
        planTask(managerId, taskId, priority, storyId);
    }

    private String cleanDescription(String value) {
        return value == null || value.isBlank() ? "暂无描述" : value.trim();
    }
}
