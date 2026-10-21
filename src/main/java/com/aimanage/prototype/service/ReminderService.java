package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReminderService {
    private final UserRepository users;
    private final TaskRepository tasks;
    private final ProjectMemberRepository members;
    private final TaskReminderReadRepository reads;

    public ReminderService(UserRepository users, TaskRepository tasks, ProjectMemberRepository members,
                           TaskReminderReadRepository reads) {
        this.users = users; this.tasks = tasks; this.members = members; this.reads = reads;
    }

    @Transactional(readOnly = true)
    public List<Reminder> unread(Long developerId) {
        User user = requireDeveloper(developerId);
        LocalDate today = LocalDate.now();
        return tasks.findByAssigneeIdOrderByUpdatedAtDesc(user.getId()).stream()
                .filter(task -> eligible(task, developerId))
                .filter(task -> !task.getDueDate().isAfter(today.plusDays(3)))
                .filter(task -> !reads.existsByUserIdAndTaskIdAndDueDate(developerId, task.getId(), task.getDueDate()))
                .map(task -> new Reminder(task, task.getDueDate().isBefore(today)))
                .sorted((a, b) -> a.task().getDueDate().compareTo(b.task().getDueDate())).toList();
    }

    @Transactional
    public void markRead(Long developerId, Long taskId) {
        User user = requireDeveloper(developerId);
        Task task = tasks.findById(taskId).orElseThrow(() -> new BusinessException("任务不存在"));
        if (!task.getAssignee().getId().equals(developerId) || !eligible(task, developerId))
            throw new BusinessException("只能处理自己的有效任务提醒");
        if (!reads.existsByUserIdAndTaskIdAndDueDate(developerId, taskId, task.getDueDate()))
            reads.save(new TaskReminderRead(user, task));
    }

    private User requireDeveloper(Long id) {
        User user = users.findById(id).orElseThrow(() -> new BusinessException("用户不存在"));
        if (user.getRole() != Role.DEVELOPER) throw new BusinessException("只有开发人员可以处理本人提醒");
        return user;
    }

    private boolean eligible(Task task, Long userId) {
        if (task.getStatus() == TaskStatus.DONE || task.getDueDate() == null) return false;
        if (task.getProject().getStatus() == ProjectStatus.PAUSED || task.getProject().getStatus() == ProjectStatus.COMPLETED) return false;
        return members.findByProjectIdAndUserId(task.getProject().getId(), userId)
                .map(member -> member.getStatus() == MemberStatus.ACTIVE).orElse(false);
    }

    public record Reminder(Task task, boolean overdue) {}
}
