package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.PlatformSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/** Key-value platform configuration; the key is the primary key. */
public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
