package com.rishav.order.service.impl;

import com.rishav.order.config.JwtUtil;
import com.rishav.order.constant.OrderStatus;
import com.rishav.order.dto.PaymentRequest;
import com.rishav.order.dto.ProductDTO;
import com.rishav.order.exception.DownstreamUnavailableException;
import com.rishav.order.exception.IdempotencyKeyConflictException;
import com.rishav.order.exception.InvalidIdempotencyKeyException;
import com.rishav.order.exception.OrderNotFoundException;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final JwtUtil jwtUtil;
    private final RetryTemplate paymentRetryTemplate;

    @Value("${order.payment-timeout:PT5M}")
    private Duration paymentTimeout;

    @Value("${order.idempotency-key-ttl:PT24H}")
    private Duration idempotencyKeyTtl;

    // Deliberately not @Transactional: each save must commit before the payment call, because the
    // circuit breaker runs that call on another thread and the payment-events consumer can process
    // the result before the call returns.
    @Override
    public Order placeOrder(Order order, String idempotencyKey) {
        if (idempotencyKey != null) {
            if (idempotencyKey.isBlank() || idempotencyKey.length() > Order.IDEMPOTENCY_KEY_MAX_LENGTH) {
                throw new InvalidIdempotencyKeyException(Order.IDEMPOTENCY_KEY_MAX_LENGTH);
            }
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return sameRequestOrConflict(existing.get(), order);
            }
        }

        String userUrl = "http://USER-SERVICE/api/users/" + order.getUserId();
        Object userResponse = fetch(userUrl, Object.class, "user-service");
        if (userResponse == null) {
            throw new RuntimeException("User not found with ID: " + order.getUserId());
        }

        String productUrl = "http://PRODUCT-SERVICE/api/products/" + order.getProductId();
        ProductDTO product = fetch(productUrl, ProductDTO.class, "product-service");
        if (product == null) {
            throw new RuntimeException("Product not found with ID: " + order.getProductId());
        }

        order.setTotalPrice(Math.multiplyExact(product.price(), order.getQuantity()));
        order.setStatus(OrderStatus.PLACED);
        order.setCreatedAt(LocalDateTime.now());
        order.setIdempotencyKey(idempotencyKey);
        Order savedOrder;
        try {
            savedOrder = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key saved first, so return its order and skip this payment.
            if (idempotencyKey == null) {
                throw e;
            }
            Order winner = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return sameRequestOrConflict(winner, order);
        }

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
                    // Inside the breaker, so it records one result per order and an open circuit isn't retried.
                    paymentRetryTemplate.invoke(() ->
                            restTemplate.postForObject("http://PAYMENT-SERVICE/api/payments", entity, Object.class));
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

    private static Order sameRequestOrConflict(Order existing, Order request) {
        boolean same = Objects.equals(existing.getUserId(), request.getUserId())
                && Objects.equals(existing.getProductId(), request.getProductId())
                && Objects.equals(existing.getQuantity(), request.getQuantity());
        if (!same) {
            throw new IdempotencyKeyConflictException(existing.getId());
        }
        return existing;
    }

    // A 4xx still propagates, so an unknown user or product stays a 404. IllegalStateException is the load
    // balancer's error when the service has no registered instance.
    private <T> T fetch(String url, Class<T> type, String service) {
        try {
            return restTemplate.getForObject(url, type);
        } catch (ResourceAccessException | HttpServerErrorException | IllegalStateException e) {
            throw new DownstreamUnavailableException(service, e);
        }
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

    // A client retries within seconds or minutes, so a key older than its time to live only blocks reuse.
    // After expiry, the same key places a new order.
    @Transactional
    @Override
    public int expireIdempotencyKeys() {
        int expired = orderRepository.clearIdempotencyKeysCreatedBefore(LocalDateTime.now().minus(idempotencyKeyTtl));
        if (expired > 0) {
            log.info("Expired {} idempotency keys older than {}", expired, idempotencyKeyTtl);
        }
        return expired;
    }
}
