package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.Task;
import com.aimanage.prototype.domain.TaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    @EntityGraph(attributePaths = {"assignee", "project"})
    List<Task> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
    @EntityGraph(attributePaths = {"assignee", "project"})
    List<Task> findByAssigneeIdOrderByUpdatedAtDesc(Long assigneeId);
    long countByProjectOwnerId(Long ownerId);
    long countByProjectOwnerIdAndStatus(Long ownerId, TaskStatus status);
}
