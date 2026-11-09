package com.rishav.payment.dto;

import com.rishav.payment.constant.PaymentStatus;

public record PaymentEvent(String orderId, PaymentStatus status) {
}
