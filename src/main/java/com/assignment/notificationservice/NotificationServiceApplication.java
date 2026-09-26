package com.assignment.notificationservice;

import com.assignment.notificationservice.configs.DispatcherProperties;
import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.configs.PoolProperties;
import com.assignment.notificationservice.configs.RetryProperties;
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
