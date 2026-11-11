package com.rishav.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

@Configuration
public class PaymentRetryConfig {

    // Retries only failures where payment-service may not have the request or failed while handling it. A 4xx or a
    // declined payment is final. Retrying can't charge twice, because payment-service handles each orderId once.
    @Bean
    public RetryTemplate paymentRetryTemplate(
            @Value("${order.payment-retry.max-retries:2}") long maxRetries,
            @Value("${order.payment-retry.delay:200ms}") Duration delay) {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(delay)
                .multiplier(2)
                .includes(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    }
}
