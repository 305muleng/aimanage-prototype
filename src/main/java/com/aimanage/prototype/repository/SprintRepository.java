package com.aimanage.prototype.repository;
import com.aimanage.prototype.domain.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SprintRepository extends JpaRepository<Sprint,Long>{
 List<Sprint> findByProjectIdOrderByStartDateAsc(Long projectId);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select p from Project p where p.id = :id")
 java.util.Optional<com.aimanage.prototype.domain.Project> lockProject(@org.springframework.data.repository.query.Param("id") Long id);
}
