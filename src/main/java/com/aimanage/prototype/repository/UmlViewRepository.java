package com.aimanage.prototype.repository;import com.aimanage.prototype.domain.*;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface UmlViewRepository extends JpaRepository<UmlView,Long>{List<UmlView> findByProjectIdOrderByIdAsc(Long id);}
