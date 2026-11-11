package com.rishav.payment;

import com.rishav.payment.kafka.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final PaymentEventPublisher publisher = new PaymentEventPublisher(kafkaTemplate);

    @Test
    void failedSend_isReportedToTheCaller() {
        ReflectionTestUtils.setField(publisher, "topic", "payment-events");
        when(kafkaTemplate.send("payment-events", "1", "event"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThrows(RuntimeException.class, () -> publisher.publishPaymentEvent("1", "event"));
    }

    @Test
    void event_isKeyedByOrderId() {
        ReflectionTestUtils.setField(publisher, "topic", "payment-events");
        when(kafkaTemplate.send("payment-events", "42", "event")).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPaymentEvent("42", "event");

        verify(kafkaTemplate).send("payment-events", "42", "event");
    }
}
