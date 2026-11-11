package com.rishav.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 5000;

    // Spring classes DataAccessResourceFailureException as non-transient, but it's what a database outage throws,
    // so it's listed by name.
    private static final Map<Class<? extends Throwable>, Boolean> DATABASE_UNAVAILABLE = Map.of(
            TransientDataAccessException.class, true,
            DataAccessResourceFailureException.class, true,
            CannotCreateTransactionException.class, true);

    @Value("${kafka.topic.dead-letter-name:payment-events.DLT}")
    private String deadLetterTopic;

    // Malformed events are skipped before this handler. Database failures wait for recovery. Anything else is a bug or
    // an event that can't apply, so it goes to the dead-letter topic at once instead of blocking its partition, and
    // stays there for replay: dropping a SUCCESS event would leave a paid order for the sweeper to mark failed.
    @Bean
    public DefaultErrorHandler paymentEventErrorHandler(KafkaOperations<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(DATABASE_UNAVAILABLE, false);
        return handler;
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic).partitions(1).replicas(1).build();
    }
}
