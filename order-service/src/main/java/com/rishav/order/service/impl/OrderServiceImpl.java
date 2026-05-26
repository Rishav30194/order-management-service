package com.rishav.order.service.impl;

import com.rishav.order.config.JwtUtil;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.PaymentRequest;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final JwtUtil jwtUtil;

    @Transactional
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

        order.setTotalPrice(product.getPrice() * order.getQuantity());
        order.setStatus(OrderStatus.PLACED);
        order.setCreatedAt(LocalDateTime.now());
        Order savedOrder = orderRepository.save(order);

        PaymentRequest paymentRequest = PaymentRequest.builder()
                .orderId(String.valueOf(savedOrder.getId()))
                .amount(BigDecimal.valueOf(savedOrder.getTotalPrice()))
                .customerId(String.valueOf(savedOrder.getUserId()))
                .paymentMethod("MOCK")
                .build();

        return circuitBreakerFactory.create("payment-service").run(
                () -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Authorization", "Bearer " + jwtUtil.generateToken("order-service"));
                    HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, headers);
                    restTemplate.postForObject("http://PAYMENT-SERVICE/api/payments", entity, Object.class);
                    savedOrder.setStatus(OrderStatus.PAYMENT_INITIATED);
                    return orderRepository.save(savedOrder);
                },
                throwable -> {
                    log.error("Payment service unreachable for order {}: {}", savedOrder.getId(), throwable.getMessage());
                    savedOrder.setStatus(OrderStatus.PAYMENT_FAILED);
                    return orderRepository.save(savedOrder);
                }
        );
    }

    @Transactional(readOnly = true)
    @Override
    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found with ID: " + id));
    }

    @Transactional(readOnly = true)
    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
