package com.rishav.order;

import tools.jackson.databind.ObjectMapper;
import com.rishav.order.kafka.PaymentEventConsumer;
import com.rishav.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentEventConsumerTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentEventConsumer consumer = new PaymentEventConsumer(new ObjectMapper(), orderRepository);

    @Test
    void databaseFailure_reachesListenerErrorHandler() {
        when(orderRepository.updateStatusIfCurrentIn(eq(1L), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> consumer.consume("{\"orderId\":\"1\",\"status\":\"SUCCESS\"}"));
    }

    @Test
    void unknownStatus_isSkipped() {
        assertDoesNotThrow(() -> consumer.consume("{\"orderId\":\"1\",\"status\":\"REFUNDED\"}"));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void malformedEvent_isSkipped() {
        assertDoesNotThrow(() -> consumer.consume("not json"));

        verifyNoInteractions(orderRepository);
    }
}
