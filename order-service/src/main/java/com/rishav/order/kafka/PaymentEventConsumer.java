package com.rishav.order.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

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

        String orderId;
        String paymentStatus;
        try {
            JsonNode json = objectMapper.readTree(event);
            orderId = json.get("orderId").asText();
            paymentStatus = json.get("status").asText();
        } catch (Exception e) {
            logger.error("Failed to parse payment event: {}", event, e);
            return;
        }

        logger.info("Parsed event — orderId: {}, status: {}", orderId, paymentStatus);

        // Outside the try block so database failures reach the listener's error handler for retry.
        updateOrderStatus(orderId, paymentStatus);
    }

    private void updateOrderStatus(String orderId, String paymentStatus) {
        Order order = orderRepository.findById(Long.parseLong(orderId)).orElse(null);
        if (order == null) {
            logger.warn("Received payment event for unknown order id: {}", orderId);
            return;
        }
        OrderStatus newStatus = "SUCCESS".equals(paymentStatus) ? OrderStatus.PAYMENT_SUCCESS : OrderStatus.PAYMENT_FAILED;
        order.setStatus(newStatus);
        orderRepository.save(order);
        logger.info("Order [{}] status updated to: {}", orderId, newStatus);
    }
}
