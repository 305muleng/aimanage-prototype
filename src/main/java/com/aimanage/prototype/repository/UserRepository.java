package com.aimanage.prototype.repository;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u order by u.id")
    List<User> lockAllUsers();
    Optional<User> findByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<User> findByRoleOrderByDisplayName(Role role);
}
