package com.nubian.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Nubian AI Java application.
 * 
 * Note on Production Readiness:
 * - Thread Pools: For production, consider defining custom thread pools (e.g., using @EnableAsync and AsyncConfigurer)
 *   to manage concurrency for @Async and CompletableFuture operations, ensuring optimal performance and resource utilization.
 *   Proper error propagation across async boundaries should also be thoroughly reviewed.
 * - Configuration: While @EnableConfigurationProperties is used, further refactoring of individual @Value annotations
 *   into dedicated @ConfigurationProperties classes can improve type safety and maintainability.
 * 
 * This application implements a Java version of the Nubian AI platform,
 * which consists of two main components:
 * 
 * 1. Nubian runtime - shared tool, persistence, and execution contracts
 * 2. Agent modules - feature implementations that use those runtime contracts
 */
@SpringBootApplication(exclude = {
    RabbitAutoConfiguration.class,
    RedisAutoConfiguration.class,
    RedisReactiveAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})
public class NubianAiJavaApplication {
    private static final Logger log = LoggerFactory.getLogger(NubianAiJavaApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NubianAiJavaApplication.class, args);
        log.info("Nubian AI Java application started successfully");
    }
}
