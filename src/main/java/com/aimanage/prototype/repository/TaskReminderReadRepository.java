package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.TaskReminderRead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface TaskReminderReadRepository extends JpaRepository<TaskReminderRead, Long> {
    boolean existsByUserIdAndTaskIdAndDueDate(Long userId, Long taskId, LocalDate dueDate);
}
