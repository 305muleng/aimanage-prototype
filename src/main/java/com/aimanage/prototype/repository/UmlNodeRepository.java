package com.aimanage.prototype.repository;import com.aimanage.prototype.domain.*;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface UmlNodeRepository extends JpaRepository<UmlNode,Long>{
 List<UmlNode> findByViewIdOrderByIdAsc(Long id);
 @org.springframework.data.jpa.repository.EntityGraph(attributePaths={"view","story","task","task.story"})
 List<UmlNode> findByViewProjectId(Long projectId);
}
