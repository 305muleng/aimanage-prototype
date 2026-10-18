package com.aimanage.prototype.repository;import com.aimanage.prototype.domain.*;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface UmlEdgeRepository extends JpaRepository<UmlEdge,Long>{List<UmlEdge> findByViewIdOrderByIdAsc(Long id);}
