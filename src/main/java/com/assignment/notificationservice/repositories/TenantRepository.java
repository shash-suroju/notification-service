package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Platform-level data: tenants are managed by the platform admin, so no tenant scoping applies. */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);

    Page<Tenant> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
