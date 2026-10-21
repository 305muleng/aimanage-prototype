package com.aimanage.prototype.repository;
import com.aimanage.prototype.domain.ManagedRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ManagedRoleRepository extends JpaRepository<ManagedRole,Long> {
    Optional<ManagedRole> findByBuiltInCode(String code);
    boolean existsByNameIgnoreCase(String name);
    List<ManagedRole> findAllByOrderByIdAsc();
}
