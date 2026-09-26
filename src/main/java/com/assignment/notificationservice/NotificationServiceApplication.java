package com.assignment.notificationservice;

import com.assignment.notificationservice.config.DispatcherProperties;
import com.assignment.notificationservice.config.MockProviderProperties;
import com.assignment.notificationservice.config.PoolProperties;
import com.assignment.notificationservice.config.RetryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        DispatcherProperties.class,
        PoolProperties.class,
        RetryProperties.class,
        MockProviderProperties.class
})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
