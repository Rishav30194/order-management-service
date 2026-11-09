package com.rishav.order.config;

import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class CircuitBreakerConfig {

    // The breaker runs each call on its own executor, which spring.threads.virtual.enabled doesn't cover.
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> virtualThreadCircuitBreakerExecutor() {
        return factory -> factory.configureExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    }
}
