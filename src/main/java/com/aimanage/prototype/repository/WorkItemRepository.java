package com.aimanage.prototype.repository;
import com.aimanage.prototype.domain.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface WorkItemRepository extends JpaRepository<WorkItem,Long> {
 @org.springframework.data.jpa.repository.EntityGraph(attributePaths={"requirement","sprint"})
 List<WorkItem> findByProjectIdOrderByIdDesc(Long projectId);
}
