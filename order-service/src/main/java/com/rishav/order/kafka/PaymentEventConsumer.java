package com.rishav.order.kafka;

import com.rishav.order.dto.PaymentEvent;
import tools.jackson.databind.ObjectMapper;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

@Service
public class PaymentEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;

    public PaymentEventConsumer(ObjectMapper objectMapper, OrderRepository orderRepository) {
        this.objectMapper = objectMapper;
        this.orderRepository = orderRepository;
    }

    @KafkaListener(topics = "${kafka.topic.name}", groupId = "order-group")
    public void consume(String event) {
        logger.info("Received payment event: {}", event);

        PaymentEvent paymentEvent;
        try {
            paymentEvent = objectMapper.readValue(event, PaymentEvent.class);
        } catch (Exception e) {
            logger.error("Failed to parse payment event: {}", event, e);
            return;
        }

        logger.info("Parsed event: {}", paymentEvent);

        // Outside the try block so database failures reach the listener's error handler for retry.
        updateOrderStatus(paymentEvent);
    }

    // PAYMENT_SUCCESS is final. A late SUCCESS still replaces a timeout's PAYMENT_FAILED, but no event undoes a success.
    private void updateOrderStatus(PaymentEvent event) {
        OrderStatus newStatus = switch (event.status()) {
            case SUCCESS -> OrderStatus.PAYMENT_SUCCESS;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
        };
        Set<OrderStatus> allowedFrom = switch (event.status()) {
            case SUCCESS -> EnumSet.of(OrderStatus.PAYMENT_INITIATED, OrderStatus.PAYMENT_FAILED);
            case FAILED -> EnumSet.of(OrderStatus.PAYMENT_INITIATED);
        };

        if (orderRepository.updateStatusIfCurrentIn(event.orderId(), allowedFrom, newStatus) == 1) {
            logger.info("Order [{}] status updated to: {}", event.orderId(), newStatus);
            return;
        }

        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            logger.warn("Received payment event for unknown order id: {}", event.orderId());
        } else if (order.getStatus() == newStatus) {
            logger.info("Order [{}] is already {}; duplicate event ignored", event.orderId(), newStatus);
        } else {
            logger.warn("Ignored {} event for order [{}] in status {}", event.status(), event.orderId(), order.getStatus());
        }
    }
}
