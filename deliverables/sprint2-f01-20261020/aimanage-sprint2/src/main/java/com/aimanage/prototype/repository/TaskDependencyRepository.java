package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.TaskDependency;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {
    @EntityGraph(attributePaths = {"task", "predecessor"})
    List<TaskDependency> findByTaskProjectId(Long projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TaskDependency d where d.task.project.id = :projectId")
    List<TaskDependency> lockProjectDependencies(@org.springframework.data.repository.query.Param("projectId") Long projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TaskDependency> findByTaskIdAndPredecessorId(Long taskId, Long predecessorId);
}
