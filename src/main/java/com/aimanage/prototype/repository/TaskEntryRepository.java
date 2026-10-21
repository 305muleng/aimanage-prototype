package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.TaskEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskEntryRepository extends JpaRepository<TaskEntry, Long> {
    List<TaskEntry> findByTaskIdOrderByCreatedAtDescIdDesc(Long taskId);
}
