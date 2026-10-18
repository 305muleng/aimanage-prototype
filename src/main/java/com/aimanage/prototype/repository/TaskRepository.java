package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.domain.TaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    boolean existsByProjectIdAndAssigneeId(Long projectId, Long assigneeId);
    @EntityGraph(attributePaths = {"assignee", "project", "story", "story.sprint", "story.requirement"})
    List<Task> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
    @EntityGraph(attributePaths = {"assignee", "project", "story", "story.sprint", "story.requirement"})
    List<Task> findByAssigneeIdOrderByUpdatedAtDesc(Long assigneeId);
    long countByProjectOwnerId(Long ownerId);
    long countByProjectOwnerIdAndStatus(Long ownerId, TaskStatus status);
}
