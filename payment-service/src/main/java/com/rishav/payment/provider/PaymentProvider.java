package com.rishav.payment.provider;

import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;

public interface PaymentProvider {

    PaymentResponseDto processPayment(PaymentRequest request);
}
