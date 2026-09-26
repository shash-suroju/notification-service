package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findByTenantIdAndRoleOrderByCreatedAtAsc(UUID tenantId, Role role);
}
