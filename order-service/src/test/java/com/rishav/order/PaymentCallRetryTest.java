package com.rishav.order;

import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The test application.properties shadows the main one, so load the main file to test the real retry and breaker settings.
@SpringBootTest
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class PaymentCallRetryTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @BeforeEach
    void stubLookups() {
        when(restTemplate.getForObject(contains("/api/users/"), eq(Object.class))).thenReturn(Map.of("id", 1));
        when(restTemplate.getForObject(contains("/api/products/"), eq(ProductDTO.class)))
                .thenReturn(new ProductDTO(1L, "Widget", "A widget", 100L, 5, "Tools"));
    }

    @Test
    void transientFailureThenSuccess_isRetried() {
        when(paymentCall()).thenThrow(new ResourceAccessException("connection reset")).thenReturn(Map.of());

        Order placed = placeOrder();

        assertEquals(OrderStatus.PAYMENT_INITIATED, storedStatus(placed));
        verify(restTemplate, times(2)).postForObject(contains("/api/payments"), any(), eq(Object.class));
    }

    @Test
    void serverErrorOnEveryTry_failsAfterThreeTries() {
        when(paymentCall()).thenThrow(error(HttpStatus.SERVICE_UNAVAILABLE));

        Order placed = placeOrder();

        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(placed));
        verify(restTemplate, times(3)).postForObject(contains("/api/payments"), any(), eq(Object.class));
    }

    @Test
    void clientError_isNotRetried() {
        when(paymentCall()).thenThrow(error(HttpStatus.FORBIDDEN));

        Order placed = placeOrder();

        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(placed));
        verify(restTemplate, times(1)).postForObject(contains("/api/payments"), any(), eq(Object.class));
    }

    // Five failures fill the breaker's sliding window, so counting them would open it and skip the sixth call.
    @Test
    void clientErrors_doNotOpenCircuit() {
        HttpClientErrorException forbidden = error(HttpStatus.FORBIDDEN);
        when(paymentCall()).thenThrow(forbidden, forbidden, forbidden, forbidden, forbidden).thenReturn(Map.of());
        for (int i = 0; i < 5; i++) {
            placeOrder();
        }

        Order placed = placeOrder();

        assertEquals(OrderStatus.PAYMENT_INITIATED, storedStatus(placed));
    }

    private Object paymentCall() {
        return restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class));
    }

    @SuppressWarnings("unchecked")
    private static <E extends RuntimeException> E error(HttpStatus status) {
        return (E) (status.is4xxClientError()
                ? HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null)
                : HttpServerErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null));
    }

    private Order placeOrder() {
        return orderService.placeOrder(Order.builder().userId(1L).productId(1L).quantity(2).build());
    }

    private OrderStatus storedStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
