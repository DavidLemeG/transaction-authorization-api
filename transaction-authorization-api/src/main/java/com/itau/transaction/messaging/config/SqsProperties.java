package com.itau.transaction.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.aws")
public record SqsProperties(
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        String queueName,
        int consumerThreads
) {
}