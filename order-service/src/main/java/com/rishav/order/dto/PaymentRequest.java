package com.rishav.order.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record PaymentRequest(String orderId, BigDecimal amount, String customerId, String paymentMethod) {
}
