package com.rishav.payment.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.rishav.payment.dto.PaymentEvent;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import com.rishav.payment.entity.PaymentResponse;
import com.rishav.payment.kafka.PaymentEventPublisher;
import com.rishav.payment.provider.PaymentProvider;
import com.rishav.payment.repository.PaymentResponseRepository;
import com.rishav.payment.service.PaymentService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentProvider paymentProvider;
    private final PaymentResponseRepository repository;
    private final PaymentEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public PaymentResponseDto handlePayment(PaymentRequest request) {
        PaymentResponseDto response = paymentProvider.processPayment(request);

        PaymentResponse saved = PaymentResponse.builder()
                .transactionId(response.transactionId())
                .orderId(response.orderId())
                .status(response.status())
                .message(response.message())
                .build();

        repository.save(saved);

        String eventJson = objectMapper.writeValueAsString(new PaymentEvent(response.orderId(), response.status()));

        eventPublisher.publishPaymentEvent(eventJson);

        return response;
    }
}
