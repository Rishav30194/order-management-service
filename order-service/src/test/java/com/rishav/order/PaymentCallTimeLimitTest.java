package com.rishav.order;

import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// The test application.properties shadows the main one, so load the main file to test the real resilience4j settings.
@SpringBootTest
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class PaymentCallTimeLimitTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    // Resilience4j's default 1-second time limit fails this call, so it checks that the configured limit is applied.
    @Test
    void slowPaymentCall_withinLimit_staysInitiated() {
        when(restTemplate.getForObject(contains("/api/users/"), eq(Object.class))).thenReturn(Map.of("id", 1));
        when(restTemplate.getForObject(contains("/api/products/"), eq(ProductDTO.class)))
                .thenReturn(new ProductDTO(1L, "Widget", "A widget", 100L, 5, "Tools"));
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenAnswer(call -> {
            Thread.sleep(1500);
            return Map.of();
        });

        Order placed = orderService.placeOrder(Order.builder().userId(1L).productId(1L).quantity(2).build());

        assertEquals(OrderStatus.PAYMENT_INITIATED, placed.getStatus());
        assertEquals(OrderStatus.PAYMENT_INITIATED, orderRepository.findById(placed.getId()).orElseThrow().getStatus());
    }
}
