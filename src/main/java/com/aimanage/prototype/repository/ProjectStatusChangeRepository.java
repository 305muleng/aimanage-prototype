package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.ProjectStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProjectStatusChangeRepository extends JpaRepository<ProjectStatusChange, Long> {
    List<ProjectStatusChange> findByProjectIdOrderByChangedAtDescIdDesc(Long projectId);
}
