package com.rishav.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdempotencyKeyExpirer {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${order.idempotency-key-expiry-interval:PT1H}")
    public void expire() {
        orderService.expireIdempotencyKeys();
    }
}
