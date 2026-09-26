package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.ChannelConfig;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, UUID> {

    List<ChannelConfig> findByTenantId(UUID tenantId);

    Optional<ChannelConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);
}
