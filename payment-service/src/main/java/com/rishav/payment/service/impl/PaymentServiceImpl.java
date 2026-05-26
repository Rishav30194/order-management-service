package com.rishav.payment.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import com.rishav.payment.entity.PaymentResponse;
import com.rishav.payment.kafka.PaymentEventPublisher;
import com.rishav.payment.provider.MockPaymentProvider;
import com.rishav.payment.provider.PaymentProvider;
import com.rishav.payment.repository.PaymentResponseRepository;
import com.rishav.payment.service.PaymentService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@AllArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentProvider paymentProvider;
    private final PaymentResponseRepository repository;
    private PaymentEventPublisher eventPublisher;

    ObjectMapper mapper = new ObjectMapper();

    @Override
    public PaymentResponseDto handlePayment(PaymentRequest request) throws JsonProcessingException {
        PaymentResponseDto response = paymentProvider.processPayment(request);

        PaymentResponse saved = PaymentResponse.builder()
                .transactionId(response.getTransactionId())
                .orderId(response.getOrderId())
                .status(response.getStatus())
                .message(response.getMessage())
                .build();

        repository.save(saved);

        Map<String, String> event = new HashMap<>();
        event.put("orderId", response.getOrderId());
        event.put("status", String.valueOf(response.getStatus()));
        String eventJson = mapper.writeValueAsString(event);

        eventPublisher.publishPaymentEvent(eventJson);

        return response;
    }

}
