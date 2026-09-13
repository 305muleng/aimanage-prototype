package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndUserId(Long projectId, Long userId);
    @EntityGraph(attributePaths = "user")
    List<ProjectMember> findByProjectIdOrderByUserDisplayName(Long projectId);
    long countByProjectId(Long projectId);
}
