package com.rishav.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rishav.order.kafka.PaymentEventConsumer;
import com.rishav.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentEventConsumerTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentEventConsumer consumer = new PaymentEventConsumer(new ObjectMapper(), orderRepository);

    @Test
    void databaseFailure_reachesListenerErrorHandler() {
        when(orderRepository.findById(1L)).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> consumer.consume("{\"orderId\":\"1\",\"status\":\"SUCCESS\"}"));
    }

    @Test
    void malformedEvent_isSkipped() {
        assertDoesNotThrow(() -> consumer.consume("not json"));

        verifyNoInteractions(orderRepository);
    }
}
