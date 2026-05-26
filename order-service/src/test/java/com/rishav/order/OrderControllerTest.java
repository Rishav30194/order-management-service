package com.rishav.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.model.Order;
import com.rishav.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "jwt.secret=dGhpcyBpcyBhIHNlY3JldCBrZXkgZm9yIHRoZSBvcmRlciBtYW5hZ2VtZW50IHNlcnZpY2U=",
        "jwt.expiration-ms=86400000",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.kafka.consumer.group-id=order-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.auto-startup=false",
        "kafka.topic.name=payment-events"
})
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    void placeOrder_happyPath() throws Exception {
        Order input = Order.builder()
                .userId(1L)
                .productId(1L)
                .quantity(2)
                .build();

        Order placed = Order.builder()
                .id(1L)
                .userId(1L)
                .productId(1L)
                .quantity(2)
                .totalPrice(1000L)
                .status(OrderStatus.PAYMENT_INITIATED)
                .createdAt(LocalDateTime.now())
                .build();

        when(orderService.placeOrder(any(Order.class))).thenReturn(placed);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PAYMENT_INITIATED"));
    }

    @Test
    void getOrderById_happyPath() throws Exception {
        Order order = Order.builder()
                .id(42L)
                .userId(1L)
                .productId(2L)
                .quantity(3)
                .totalPrice(3000L)
                .status(OrderStatus.PAYMENT_SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        when(orderService.getOrderById(eq(42L))).thenReturn(order);

        mockMvc.perform(get("/api/orders/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PAYMENT_SUCCESS"));
    }

    @Test
    void getAllOrders_returnsNonNullList() throws Exception {
        when(orderService.getAllOrders()).thenReturn(List.of());

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
