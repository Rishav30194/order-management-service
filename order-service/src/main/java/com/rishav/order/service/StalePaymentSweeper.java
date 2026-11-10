package com.rishav.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Catches every case where a payment result never arrives, including malformed events with no usable order ID.
// A valid event that arrives later still sets the correct final status.
@Component
@RequiredArgsConstructor
public class StalePaymentSweeper {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${order.stale-payment-sweep-interval:PT1M}")
    public void sweep() {
        orderService.failStalePayments();
    }
}
