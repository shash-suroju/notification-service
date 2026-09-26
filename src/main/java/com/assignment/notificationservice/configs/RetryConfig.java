package com.assignment.notificationservice.configs;

import com.assignment.notificationservice.utils.ExponentialBackoffWithJitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryConfig {

    @Bean
    public ExponentialBackoffWithJitter backoffPolicy(RetryProperties retryProperties) {
        return new ExponentialBackoffWithJitter(retryProperties.getBaseDelayMs(), retryProperties.getMaxDelayMs());
    }
}
