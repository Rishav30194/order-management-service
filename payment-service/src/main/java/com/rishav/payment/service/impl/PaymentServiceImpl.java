package com.rishav.payment.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.rishav.payment.dto.PaymentEvent;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import com.rishav.payment.entity.OutboxEvent;
import com.rishav.payment.entity.PaymentResponse;
import com.rishav.payment.exception.PaymentConflictException;
import com.rishav.payment.provider.PaymentProvider;
import com.rishav.payment.repository.OutboxEventRepository;
import com.rishav.payment.repository.PaymentResponseRepository;
import com.rishav.payment.service.PaymentService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@AllArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentProvider paymentProvider;
    private final PaymentResponseRepository repository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public PaymentResponseDto handlePayment(PaymentRequest request) {
        Optional<PaymentResponse> existing = repository.findByOrderId(request.orderId());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        PaymentResponseDto response = paymentProvider.processPayment(request);

        PaymentResponse saved = PaymentResponse.builder()
                .transactionId(response.transactionId())
                .orderId(response.orderId())
                .amount(request.amount())
                .status(response.status())
                .message(response.message())
                .build();

        repository.save(saved);

        // Saved in this transaction instead of sent to Kafka here: a send that times out can still reach the broker
        // after a rollback. OutboxRelay publishes the row once this commits.
        String eventJson = objectMapper.writeValueAsString(new PaymentEvent(response.orderId(), response.status()));
        outboxEventRepository.save(OutboxEvent.builder().orderId(response.orderId()).payload(eventJson).build());

        return response;
    }

    // Returns the original result and writes no outbox row: the first request's event already covers this payment.
    private PaymentResponseDto replay(PaymentResponse payment, PaymentRequest request) {
        // compareTo, not equals, so 500 and 500.00 count as the same amount.
        if (payment.getAmount().compareTo(request.amount()) != 0) {
            throw new PaymentConflictException("Order " + request.orderId() + " was already paid with amount "
                    + payment.getAmount() + ", not " + request.amount());
        }
        return PaymentResponseDto.builder()
                .transactionId(payment.getTransactionId())
                .orderId(payment.getOrderId())
                .status(payment.getStatus())
                .message(payment.getMessage())
                .build();
    }
}
