package com.rishav.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record PaymentRequest(
        String paymentRequestId,
        @NotBlank String orderId,
        @NotBlank String paymentMethod,
        @NotNull @Positive BigDecimal amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @NotBlank String customerId,
        String note) {
}
