package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.ApiKey;
import com.assignment.notificationservice.models.enums.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Auth lookup. Not tenant-scoped by design: the key itself is what identifies the tenant. */
    Optional<ApiKey> findByPrefixAndStatus(String prefix, ApiKeyStatus status);
}
