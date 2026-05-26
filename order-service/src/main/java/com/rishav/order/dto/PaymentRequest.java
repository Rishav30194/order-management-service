package com.rishav.order.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PaymentRequest {
    private String orderId;
    private BigDecimal amount;
    private String customerId;
    private String paymentMethod;
}
