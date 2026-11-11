package com.rishav.order;

import com.rishav.order.config.KafkaConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventErrorHandlerTest {

    private static final String DEAD_LETTER_TOPIC = "payment-events.DLT";

    @SuppressWarnings("unchecked")
    private final KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    private final DefaultErrorHandler handler = errorHandler();

    @Test
    void databaseFailures_areRetried() {
        assertTrue(retried(new DataAccessResourceFailureException("database unavailable")));
        assertTrue(retried(new CannotCreateTransactionException("no connection")));
        assertTrue(retried(new QueryTimeoutException("query timed out")));
    }

    @Test
    void otherFailures_areNotRetried() {
        assertFalse(retried(new NullPointerException()));
        assertFalse(retried(new IllegalArgumentException("bad event")));
    }

    @Test
    void failureThatIsNotRetried_goesToDeadLetterTopic() {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        ConsumerRecord<String, String> consumerRecord =
                new ConsumerRecord<>("payment-events", 0, 7L, null, "{\"orderId\":\"1\",\"status\":\"SUCCESS\"}");

        boolean handled = handler.handleOne(listenerFailure(new NullPointerException()), consumerRecord,
                mock(Consumer.class), mock(MessageListenerContainer.class));

        assertTrue(handled);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertEquals(DEAD_LETTER_TOPIC, sent.getValue().topic());
        assertEquals(consumerRecord.value(), sent.getValue().value());
    }

    private DefaultErrorHandler errorHandler() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "deadLetterTopic", DEAD_LETTER_TOPIC);
        return config.paymentEventErrorHandler(template);
    }

    // The container wraps every listener exception, so classification has to look through the wrapper.
    private boolean retried(Exception cause) {
        ExceptionMatcher matcher = ReflectionTestUtils.invokeMethod(handler, "getExceptionMatcher");
        return matcher.match(listenerFailure(cause));
    }

    private static ListenerExecutionFailedException listenerFailure(Exception cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }
}
