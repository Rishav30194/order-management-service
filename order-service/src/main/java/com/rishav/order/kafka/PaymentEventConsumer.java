package com.rishav.order.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PaymentEventConsumer.class);

    @KafkaListener(topics = "${kafka.topic.name}", groupId = "order-group")
    public void consume(String event){
        logger.info("Received paymentevent: {}", event);

        try{
            JSONObject json = new JSONObject(event);
            String orderId = json.getString("orderId");
            String status = json.getString("status");

            logger.info("Parse evetn = orderId: {}, status: {}", orderId, status);

            //TODO: call order service to update status in DB
            updateOrderStatus(orderId, status);

        }catch(Exception e){
            logger.error("Failed to parse payment event: {}", event, e);
        }
    }

    private void updateOrderStatus(String orderId, String status){
        // Placeholder for business logic (e.g., DB update or service call)
        logger.info("Updating order [{}] with payment status: {}", orderId, status);
    }
}
