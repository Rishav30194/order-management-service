package com.rishav.payment.dto;

import jakarta.validation.constraints.Digits;
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
        // Two decimal places at most, so the stored amount compares equal to the requested one on a repeat request.
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @NotBlank String customerId,
        String note) {
}
