package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.Template;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is tenant-scoped: there is no way to read a template by ID alone. */
public interface TemplateRepository extends JpaRepository<Template, UUID> {

    /** Latest version of each (code, channel) pair for a tenant. */
    @Query(value = """
            SELECT t FROM Template t
            WHERE t.tenant.id = :tenantId
            AND t.version = (
                SELECT MAX(t2.version) FROM Template t2
                WHERE t2.tenant.id = t.tenant.id
                AND t2.code = t.code AND t2.channel = t.channel
            )
            ORDER BY t.code, t.channel
            """,
            countQuery = """
            SELECT COUNT(t) FROM Template t
            WHERE t.tenant.id = :tenantId
            AND t.version = (
                SELECT MAX(t2.version) FROM Template t2
                WHERE t2.tenant.id = t.tenant.id
                AND t2.code = t.code AND t2.channel = t.channel
            )
            """)
    Page<Template> findLatestByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    Optional<Template> findByIdAndTenantId(UUID id, UUID tenantId);

    /** The version ingestion will render: highest active version for (tenant, code, channel). */
    @Query("""
            SELECT t FROM Template t
            WHERE t.tenant.id = :tenantId
            AND t.code = :code AND t.channel = :channel
            AND t.active = true
            ORDER BY t.version DESC
            LIMIT 1
            """)
    Optional<Template> findLatestActive(
            @Param("tenantId") UUID tenantId,
            @Param("code") String code,
            @Param("channel") Channel channel);

    /** 0 when no version exists yet. */
    @Query("""
            SELECT COALESCE(MAX(t.version), 0) FROM Template t
            WHERE t.tenant.id = :tenantId
            AND t.code = :code AND t.channel = :channel
            """)
    int findMaxVersion(
            @Param("tenantId") UUID tenantId,
            @Param("code") String code,
            @Param("channel") Channel channel);

    List<Template> findByTenantIdAndCodeAndChannelOrderByVersionDesc(
            UUID tenantId, String code, Channel channel);
}
