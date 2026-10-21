package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class TaskCollaborationService {
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final ProjectMemberRepository members;
    private final TaskEntryRepository entries;
    private final TaskDependencyRepository dependencies;
    private final ProjectTaskService core;
    private final TaskJournal journal;
    private final EntityManager entityManager;

    public TaskCollaborationService(TaskRepository tasks, ProjectRepository projects, UserRepository users,
            ProjectMemberRepository members, TaskEntryRepository entries, TaskDependencyRepository dependencies,
            ProjectTaskService core, TaskJournal journal, EntityManager entityManager) {
        this.tasks = tasks; this.projects = projects; this.users = users; this.members = members;
        this.entries = entries; this.dependencies = dependencies; this.core = core;
        this.journal = journal; this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<ProjectOption> visibleProjects(Long userId) {
        User user = user(userId);
        if (user.getRole() == Role.PROJECT_MANAGER)
            return projects.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                    .map(p -> new ProjectOption(p.getId(), p.getName())).toList();
        if (user.getRole() == Role.DEVELOPER)
            return members.findByUserId(userId).stream()
                    .map(m -> new ProjectOption(m.getProject().getId(), m.getProject().getName())).toList();
        throw new BusinessException("无权访问项目任务");
    }

    /** 先限制项目可见范围，再应用筛选；不能通过查询参数扩大读取权限。 */
    @Transactional(readOnly = true)
    public List<TaskView> search(Long userId, Long projectId, Filter filter) {
        visible(userId, projectId);
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
            throw new BusinessException("截止日期范围不正确");
        String keyword = filter.keyword() == null ? "" : filter.keyword().trim().toLowerCase(Locale.ROOT);
        if (keyword.length() > 120) throw new BusinessException("搜索关键词最多120个字符");
        Comparator<Task> order = switch (filter.sort() == null ? "updated" : filter.sort()) {
            case "updated" -> Comparator.comparing(Task::getUpdatedAt).reversed();
            case "due" -> Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "priority" -> Comparator.comparing(Task::getPriority).reversed();
            case "title" -> Comparator.comparing(Task::getTitle);
            default -> throw new BusinessException("无效的排序方式");
        };
        return tasks.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
                .filter(t -> keyword.isEmpty() || t.getTitle().toLowerCase(Locale.ROOT).contains(keyword)
                        || t.getDescription().toLowerCase(Locale.ROOT).contains(keyword))
                .filter(t -> filter.assigneeId() == null || t.getAssignee().getId().equals(filter.assigneeId()))
                .filter(t -> filter.status() == null || t.getStatus() == filter.status())
                .filter(t -> filter.priority() == null || t.getPriority() == filter.priority())
                .filter(t -> filter.blocked() == null || t.isBlocked() == filter.blocked())
                .filter(t -> filter.from() == null || t.getDueDate() != null && !t.getDueDate().isBefore(filter.from()))
                .filter(t -> filter.to() == null || t.getDueDate() != null && !t.getDueDate().isAfter(filter.to()))
                .sorted(order.thenComparing(Task::getId))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(Long userId, Long taskId) {
        Task task = task(taskId);
        User requester = user(userId);
        boolean formerAssignee = requester.getRole() == Role.DEVELOPER
                && task.getAssignee().getId().equals(userId) && task.getStatus() == TaskStatus.DONE
                && !members.existsByProjectIdAndUserId(task.getProject().getId(), userId);
        if (formerAssignee) return new Detail(view(task), task.getDescription(), List.of(), List.of(), List.of(),
                List.of(), false, false, false, false, true);
        User user = visible(userId, task.getProject().getId());
        boolean owner = isOwner(user, task.getProject());
        boolean workOpen = task.getProject().getStatus() != ProjectStatus.PAUSED
                && task.getProject().getStatus() != ProjectStatus.COMPLETED;
        boolean active = owner || active(task.getProject().getId(), userId);
        List<TaskDependency> edges = dependencies.findByTaskProjectId(task.getProject().getId());
        List<TaskView> before = edges.stream().filter(e -> e.getTask().getId().equals(taskId))
                .map(e -> view(e.getPredecessor())).toList();
        List<TaskView> after = edges.stream().filter(e -> e.getPredecessor().getId().equals(taskId))
                .map(e -> view(e.getTask())).toList();
        return new Detail(view(task), task.getDescription(), before, after,
                tasks.findByProjectIdOrderByUpdatedAtDesc(task.getProject().getId()).stream()
                        .filter(t -> !t.getId().equals(taskId)).map(this::view).toList(),
                entries.findByTaskIdOrderByCreatedAtDescIdDesc(taskId).stream()
                        .map(e -> new EntryView(e.getKind(), e.getAuthorName(), e.getContent(), e.getCreatedAt())).toList(),
                owner && workOpen, active, workOpen && active && (owner || task.getAssignee().getId().equals(userId)),
                workOpen && (owner && task.getStatus() == TaskStatus.DONE
                        || user.getRole() == Role.DEVELOPER && active && task.getAssignee().getId().equals(userId)), false);
    }

    public void comment(Long userId, Long taskId, String content) {
        Task task = task(taskId);
        User user = visible(userId, task.getProject().getId());
        requireActive(user, task.getProject());
        String text = content == null ? "" : content.trim();
        if (text.isBlank() || text.length() > 2000) throw new BusinessException("评论需为1–2000个字符");
        entries.save(new TaskEntry(task, user, "COMMENT", text));
    }

    /** 阻塞是独立标记，不新增状态、不自动禁止已有的状态更新。 */
    public void block(Long userId, Long taskId, boolean blocked, String reason) {
        Task task = task(taskId);
        requireWorkOpen(task.getProject());
        User user = visible(userId, task.getProject().getId());
        requireActive(user, task.getProject());
        if (!isOwner(user, task.getProject()) && !task.getAssignee().getId().equals(userId))
            throw new BusinessException("只有项目负责人或任务负责人可以维护阻塞");
        if (blocked && task.getStatus() == TaskStatus.DONE)
            throw new BusinessException("已完成任务不能标记阻塞，请先重开");
        String text = blocked && reason != null ? reason.trim() : "";
        if (blocked && (text.isBlank() || text.length() > 500))
            throw new BusinessException("阻塞原因需为1–500个字符");
        if (task.isBlocked() == blocked && task.getBlockedReason().equals(text)) return;
        task.setBlock(blocked, text);
        journal.record(task, userId, blocked ? "标记阻塞：" + text : "解除阻塞");
    }

    /** 同项目依赖修改串行化，防止并发的两条合法边组合成环。 */
    public void dependency(Long userId, Long taskId, Long predecessorId, boolean remove) {
        Task task = task(taskId);
        requireWorkOpen(task.getProject());
        User user = visible(userId, task.getProject().getId());
        if (!isOwner(user, task.getProject())) throw new BusinessException("只有项目负责人可以维护依赖");
        entityManager.lock(task.getProject(), LockModeType.PESSIMISTIC_WRITE);
        Task predecessor = task(predecessorId);
        if (taskId.equals(predecessorId)) throw new BusinessException("任务不能依赖自身");
        if (!task.getProject().getId().equals(predecessor.getProject().getId()))
            throw new BusinessException("依赖任务必须属于同一项目");
        var existing = dependencies.findByTaskIdAndPredecessorId(taskId, predecessorId);
        if (remove) {
            if (existing.isEmpty()) throw new BusinessException("依赖关系不存在");
            dependencies.delete(existing.get());
            journal.record(task, userId, "移除前置任务：" + predecessor.getTitle());
            return;
        }
        if (task.getStatus() == TaskStatus.DONE)
            throw new BusinessException("已完成任务新增依赖前，请先重开");
        if (existing.isPresent()) throw new BusinessException("依赖关系已存在");
        Map<Long, List<Long>> graph = new HashMap<>();
        for (var edge : dependencies.lockProjectDependencies(task.getProject().getId()))
            graph.computeIfAbsent(edge.getTask().getId(), k -> new ArrayList<>()).add(edge.getPredecessor().getId());
        if (reachable(graph, predecessorId, taskId)) throw new BusinessException("依赖关系不能形成循环");
        dependencies.save(new TaskDependency(task, predecessor));
        journal.record(task, userId, "添加前置任务：" + predecessor.getTitle());
    }

    public void status(Long userId, Long taskId, TaskStatus status) {
        core.updateStatus(userId, taskId, status);
    }

    public void status(Long userId, Long taskId, TaskStatus status, Long version, String note) {
        core.updateStatus(userId, taskId, status, version, note);
    }

    public void block(Long userId, Long taskId, boolean blocked, String reason, Long version) {
        Task task = task(taskId);
        visible(userId, task.getProject().getId());
        ProjectTaskService.requireVersion(task, version);
        block(userId, taskId, blocked, reason);
    }

    private boolean reachable(Map<Long, List<Long>> graph, Long start, Long target) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(); pending.push(start);
        while (!pending.isEmpty()) {
            Long node = pending.pop();
            if (node.equals(target)) return true;
            if (visited.add(node)) graph.getOrDefault(node, List.of()).forEach(pending::push);
        }
        return false;
    }
    private User visible(Long userId, Long projectId) {
        User user = user(userId);
        Project project = projects.findById(projectId).orElseThrow(() -> new BusinessException("项目不存在"));
        if (!isOwner(user, project) && !(user.getRole() == Role.DEVELOPER
                && members.existsByProjectIdAndUserId(projectId, userId)))
            throw new BusinessException("无权访问该项目任务");
        return user;
    }
    private boolean isOwner(User user, Project project) {
        return user.getRole() == Role.PROJECT_MANAGER && project.getOwner().getId().equals(user.getId());
    }
    private boolean active(Long projectId, Long userId) {
        return members.findByProjectIdAndUserId(projectId, userId)
                .map(m -> m.getStatus() == MemberStatus.ACTIVE).orElse(false);
    }
    private void requireActive(User user, Project project) {
        if (!isOwner(user, project) && !active(project.getId(), user.getId()))
            throw new BusinessException("暂停参与的成员只能查看任务");
    }
    private User user(Long id) {
        if (id == null) throw new BusinessException("请先登录");
        return users.findById(id).orElseThrow(() -> new BusinessException("账号不存在"));
    }
    private void requireWorkOpen(Project project) {
        if (project.getStatus() == ProjectStatus.PAUSED || project.getStatus() == ProjectStatus.COMPLETED)
            throw new BusinessException("项目已暂停或完成，请负责人先恢复为进行中");
    }
    private Task task(Long id) {
        if (id == null) throw new BusinessException("任务不存在");
        return tasks.findById(id).orElseThrow(() -> new BusinessException("任务不存在"));
    }
    private TaskView view(Task task) {
        return new TaskView(task.getId(), task.getProject().getId(), task.getProject().getName(),
                task.getTitle(), task.getAssignee().getId(), task.getAssignee().getDisplayName(),
                task.getStatus(), task.getPriority(), task.getDueDate(), task.isBlocked(), task.getBlockedReason(),
                task.getVersion(), task.getCompletionNote(), task.getCompletedAt());
    }
    public record Filter(String keyword, Long assigneeId, TaskStatus status, Priority priority,
                         LocalDate from, LocalDate to, Boolean blocked, String sort) {}
    public record ProjectOption(Long id, String name) {}
    public record TaskView(Long id, Long projectId, String projectName, String title, Long assigneeId,
                           String assigneeName, TaskStatus status, Priority priority, LocalDate dueDate,
                           boolean blocked, String blockedReason, long version, String completionNote,
                           LocalDateTime completedAt) {}
    public record EntryView(String kind, String authorName, String content, LocalDateTime createdAt) {}
    public record Detail(TaskView task, String description, List<TaskView> predecessors, List<TaskView> successors,
                          List<TaskView> candidates, List<EntryView> entries, boolean owner, boolean canComment,
                          boolean canBlock, boolean canChangeStatus, boolean historical) {}
}
