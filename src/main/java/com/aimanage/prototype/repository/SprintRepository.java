package com.aimanage.prototype.repository;
import com.aimanage.prototype.domain.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SprintRepository extends JpaRepository<Sprint,Long>{
 List<Sprint> findByProjectIdOrderByStartDateAsc(Long projectId);
}
