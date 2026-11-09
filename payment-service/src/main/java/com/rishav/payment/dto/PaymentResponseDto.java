package com.rishav.payment.dto;

import com.rishav.payment.constant.PaymentStatus;
import lombok.Builder;

@Builder
public record PaymentResponseDto(String transactionId, String orderId, PaymentStatus status, String message) {
}
