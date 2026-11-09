package com.rishav.order;

import tools.jackson.databind.ObjectMapper;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.PaymentRequest;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.kafka.PaymentEventConsumer;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlZC1vdXRzaWRlLWNp",
        "jwt.expiration-ms=86400000",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.kafka.consumer.group-id=order-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.auto-startup=false",
        "kafka.topic.name=payment-events"
})
@AutoConfigureMockMvc
class OrderPlacementTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RestTemplate restTemplate;

    @BeforeEach
    void stubLookups() {
        ProductDTO product = new ProductDTO();
        product.setPrice(100L);
        when(restTemplate.getForObject(contains("/api/users/"), eq(Object.class))).thenReturn(Map.of("id", 1));
        when(restTemplate.getForObject(contains("/api/products/"), eq(ProductDTO.class))).thenReturn(product);
    }

    @Test
    void paymentAccepted_orderIsInitiated() {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenReturn(Map.of());

        Order placed = orderService.placeOrder(newOrder());

        assertEquals(OrderStatus.PAYMENT_INITIATED, storedStatus(placed));
    }

    @Test
    void paymentEventBeforeHttpResponse_terminalStatusIsKept() {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenAnswer(call -> {
            PaymentRequest request = (PaymentRequest) ((HttpEntity<?>) call.getArgument(1)).getBody();
            String event = "{\"orderId\":\"" + request.getOrderId() + "\",\"status\":\"SUCCESS\"}";
            Thread consumerThread = new Thread(() -> paymentEventConsumer.consume(event));
            consumerThread.start();
            consumerThread.join();
            return Map.of();
        });

        Order placed = orderService.placeOrder(newOrder());

        assertEquals(OrderStatus.PAYMENT_SUCCESS, storedStatus(placed));
    }

    @Test
    void paymentServiceUnreachable_orderIsFailed() {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        Order placed = orderService.placeOrder(newOrder());

        assertEquals(OrderStatus.PAYMENT_FAILED, placed.getStatus());
        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(placed));
    }

    @Test
    void unknownUser_returns404() throws Exception {
        when(restTemplate.getForObject(contains("/api/users/"), eq(Object.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                        "User not found".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newOrder())))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    private Order newOrder() {
        return Order.builder().userId(1L).productId(1L).quantity(2).build();
    }

    private OrderStatus storedStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
