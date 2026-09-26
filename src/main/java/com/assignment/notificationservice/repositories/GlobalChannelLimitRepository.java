package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.GlobalChannelLimit;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

/** Platform-wide per-channel limits. Not tenant data, so no tenant scoping applies. */
public interface GlobalChannelLimitRepository extends JpaRepository<GlobalChannelLimit, Channel> {
}
