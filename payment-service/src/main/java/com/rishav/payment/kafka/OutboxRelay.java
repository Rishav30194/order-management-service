package com.rishav.payment.kafka;

import com.rishav.payment.entity.OutboxEvent;
import com.rishav.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Deletes a row only after Kafka acknowledges it, so delivery is at least once. A send that times out but still
// reaches the broker is sent again; order-service ignores the duplicate.
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${payment.outbox.relay-interval:PT1S}")
    public void relay() {
        for (OutboxEvent event : outboxEventRepository.findTop100ByOrderByIdAsc()) {
            try {
                eventPublisher.publishPaymentEvent(event.getPayload());
            } catch (RuntimeException e) {
                // Kafka is likely down, so the rest of the batch would fail too. The next run starts from this row.
                log.warn("Outbox event {} not published, retrying next run: {}", event.getId(), e.getMessage());
                return;
            }
            outboxEventRepository.delete(event);
        }
    }
}
