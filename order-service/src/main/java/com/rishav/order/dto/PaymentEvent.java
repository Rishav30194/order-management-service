package com.rishav.order.dto;

import com.rishav.order.constant.PaymentResult;

import java.util.Objects;

public record PaymentEvent(Long orderId, PaymentResult status) {

    public PaymentEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
    }
}
