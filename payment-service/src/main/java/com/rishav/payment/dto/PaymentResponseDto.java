package com.rishav.payment.dto;

import com.rishav.payment.constant.PaymentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentResponseDto {
    private String transactionId;
    private String orderId;
    private PaymentStatus status;
    private String message;
}

