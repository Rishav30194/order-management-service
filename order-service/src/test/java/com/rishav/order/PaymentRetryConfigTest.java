package com.rishav.order;

import com.rishav.order.config.PaymentRetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentRetryConfigTest {

    private final RetryTemplate retryTemplate = new PaymentRetryConfig()
            .paymentRetryTemplate(2, Duration.ofMillis(200), Duration.ofMillis(100));

    // The 12-second time limit assumes the two waits add up to at most 900 ms. Spring scales the jitter by the
    // multiplier, so the second wait is 400 ms plus or minus 200 ms.
    @Test
    void waits_areJitteredWithinTheTimeBudget() {
        Set<Long> firstWaits = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            BackOffExecution execution = retryTemplate.getRetryPolicy().getBackOff().start();
            long first = execution.nextBackOff();
            long second = execution.nextBackOff();
            assertTrue(first >= 100 && first <= 300, "first wait " + first);
            assertTrue(second >= 200 && second <= 600, "second wait " + second);
            firstWaits.add(first);
        }
        assertTrue(firstWaits.size() > 1, "waits never varied, so jitter isn't applied");
    }
}
