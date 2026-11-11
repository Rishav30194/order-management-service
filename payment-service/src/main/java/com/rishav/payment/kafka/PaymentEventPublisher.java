package com.rishav.payment.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class PaymentEventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topic.name}")
    private String topic;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate){
        this.kafkaTemplate = kafkaTemplate;
    }

    // Waits for the broker so OutboxRelay deletes an outbox row only after Kafka has the event.
    public void publishPaymentEvent(String eventPayload){
        try {
            kafkaTemplate.send(topic, eventPayload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing payment event", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish payment event", e);
        }
    }
}
