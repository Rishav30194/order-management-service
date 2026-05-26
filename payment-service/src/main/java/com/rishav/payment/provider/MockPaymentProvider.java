package com.rishav.payment.provider;

import com.rishav.payment.constant.PaymentStatus;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;


@Component
@Slf4j
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public PaymentResponseDto processPayment(PaymentRequest request) {
        log.info("Mocking payment for orderId: {}", request.getOrderId());

        boolean isSuccess = Math.random() > 0.2;

        return PaymentResponseDto.builder()
                .transactionId(UUID.randomUUID().toString())
                .orderId(request.getOrderId())
                .status(isSuccess ? PaymentStatus.SUCCESS : PaymentStatus.FAILED)
                .message(isSuccess ? "Payment successful" : "Payment failed due to insufficient funds")
                .build();
    }
}
