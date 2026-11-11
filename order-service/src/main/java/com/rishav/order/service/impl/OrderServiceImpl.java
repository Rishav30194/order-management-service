package com.rishav.order.service.impl;

import com.rishav.order.config.JwtUtil;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.PaymentRequest;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.exception.OrderNotFoundException;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final JwtUtil jwtUtil;

    @Value("${order.payment-timeout:PT5M}")
    private Duration paymentTimeout;

    // Deliberately not @Transactional: each save must commit before the payment call, because the
    // circuit breaker runs that call on another thread and the payment-events consumer can process
    // the result before the call returns.
    @Override
    public Order placeOrder(Order order) {
        String userUrl = "http://USER-SERVICE/api/users/" + order.getUserId();
        Object userResponse = restTemplate.getForObject(userUrl, Object.class);
        if (userResponse == null) {
            throw new RuntimeException("User not found with ID: " + order.getUserId());
        }

        String productUrl = "http://PRODUCT-SERVICE/api/products/" + order.getProductId();
        ProductDTO product = restTemplate.getForObject(productUrl, ProductDTO.class);
        if (product == null) {
            throw new RuntimeException("Product not found with ID: " + order.getProductId());
        }

        order.setTotalPrice(product.price() * order.getQuantity());
        order.setStatus(OrderStatus.PLACED);
        order.setCreatedAt(LocalDateTime.now());
        Order savedOrder = orderRepository.save(order);

        PaymentRequest paymentRequest = PaymentRequest.builder()
                .orderId(String.valueOf(savedOrder.getId()))
                .amount(BigDecimal.valueOf(savedOrder.getTotalPrice()))
                .customerId(String.valueOf(savedOrder.getUserId()))
                .paymentMethod("MOCK")
                .build();

        // Written before the call so this status can never overwrite the consumer's terminal status.
        savedOrder.setStatus(OrderStatus.PAYMENT_INITIATED);
        Order initiatedOrder = orderRepository.save(savedOrder);

        return circuitBreakerFactory.create("payment-service").run(
                () -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Authorization", "Bearer " + jwtUtil.generateToken("order-service"));
                    HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, headers);
                    restTemplate.postForObject("http://PAYMENT-SERVICE/api/payments", entity, Object.class);
                    return initiatedOrder;
                },
                throwable -> {
                    log.error("Payment service unreachable for order {}: {}", initiatedOrder.getId(), throwable.getMessage());
                    orderRepository.updateStatusIfCurrent(
                            initiatedOrder.getId(), OrderStatus.PAYMENT_INITIATED, OrderStatus.PAYMENT_FAILED);
                    return orderRepository.findById(initiatedOrder.getId()).orElseThrow();
                }
        );
    }

    @Transactional(readOnly = true)
    @Override
    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    @Override
    public int failStalePayments() {
        LocalDateTime cutoff = LocalDateTime.now().minus(paymentTimeout);
        // PLACED is included because an order stays there if placement stops between its two saves.
        int failed = orderRepository.updateStatusIfCreatedBefore(
                EnumSet.of(OrderStatus.PLACED, OrderStatus.PAYMENT_INITIATED), OrderStatus.PAYMENT_FAILED, cutoff);
        if (failed > 0) {
            log.warn("Marked {} orders PAYMENT_FAILED after no payment result for {}", failed, paymentTimeout);
        }
        return failed;
    }
}
