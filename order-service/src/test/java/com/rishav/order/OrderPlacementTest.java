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
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private DefaultErrorHandler paymentEventErrorHandler;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory;

    @MockitoBean
    private RestTemplate restTemplate;

    @BeforeEach
    void stubLookups() {
        ProductDTO product = new ProductDTO(1L, "Widget", "A widget", 100L, 5, "Tools");
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
    void paymentCall_runsOnVirtualThread() {
        AtomicBoolean virtual = new AtomicBoolean();
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenAnswer(call -> {
            virtual.set(Thread.currentThread().isVirtual());
            return Map.of();
        });

        orderService.placeOrder(newOrder());

        assertTrue(virtual.get());
    }

    @Test
    void paymentEventBeforeHttpResponse_terminalStatusIsKept() {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenAnswer(call -> {
            PaymentRequest request = (PaymentRequest) ((HttpEntity<?>) call.getArgument(1)).getBody();
            String event = "{\"orderId\":\"" + request.orderId() + "\",\"status\":\"SUCCESS\"}";
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
    void paymentTimeoutAfterSuccessEvent_keepsTerminalStatus() {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenAnswer(call -> {
            PaymentRequest request = (PaymentRequest) ((HttpEntity<?>) call.getArgument(1)).getBody();
            paymentEventConsumer.consume("{\"orderId\":\"" + request.orderId() + "\",\"status\":\"SUCCESS\"}");
            throw new ResourceAccessException("read timed out");
        });

        Order placed = orderService.placeOrder(newOrder());

        assertEquals(OrderStatus.PAYMENT_SUCCESS, storedStatus(placed));
    }

    @Test
    void stalePayment_isFailedAndLateEventStillWins() {
        Order stale = orderRepository.save(storedOrder(OrderStatus.PAYMENT_INITIATED, LocalDateTime.now().minusMinutes(10)));
        Order recent = orderRepository.save(storedOrder(OrderStatus.PAYMENT_INITIATED, LocalDateTime.now()));
        Order finished = orderRepository.save(storedOrder(OrderStatus.PAYMENT_SUCCESS, LocalDateTime.now().minusMinutes(10)));
        Order stalePlaced = orderRepository.save(storedOrder(OrderStatus.PLACED, LocalDateTime.now().minusMinutes(10)));
        Order recentPlaced = orderRepository.save(storedOrder(OrderStatus.PLACED, LocalDateTime.now()));

        orderService.failStalePayments();

        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(stale));
        assertEquals(OrderStatus.PAYMENT_INITIATED, storedStatus(recent));
        assertEquals(OrderStatus.PAYMENT_SUCCESS, storedStatus(finished));
        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(stalePlaced));
        assertEquals(OrderStatus.PLACED, storedStatus(recentPlaced));

        paymentEventConsumer.consume("{\"orderId\":\"" + stale.getId() + "\",\"status\":\"SUCCESS\"}");

        assertEquals(OrderStatus.PAYMENT_SUCCESS, storedStatus(stale));
    }

    @Test
    void failedEventAfterSuccess_keepsSuccess() {
        Order paid = orderRepository.save(storedOrder(OrderStatus.PAYMENT_SUCCESS, LocalDateTime.now()));

        paymentEventConsumer.consume("{\"orderId\":\"" + paid.getId() + "\",\"status\":\"FAILED\"}");

        assertEquals(OrderStatus.PAYMENT_SUCCESS, storedStatus(paid));
    }

    @Test
    void failedEvent_onInitiated_setsFailed() {
        Order initiated = orderRepository.save(storedOrder(OrderStatus.PAYMENT_INITIATED, LocalDateTime.now()));

        paymentEventConsumer.consume("{\"orderId\":\"" + initiated.getId() + "\",\"status\":\"FAILED\"}");

        assertEquals(OrderStatus.PAYMENT_FAILED, storedStatus(initiated));
    }

    @Test
    void listenerRetriesDatabaseFailuresUntilRecovery() {
        assertSame(paymentEventErrorHandler,
                ReflectionTestUtils.getField(kafkaListenerContainerFactory, AbstractKafkaListenerContainerFactory.class, "commonErrorHandler"));
        BackOffExecution backOff = ((BackOff) ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(paymentEventErrorHandler, "failureTracker"), "backOff")).start();
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertNotEquals(BackOffExecution.STOP, backOff.nextBackOff());
        }
    }

    @Test
    void getOrder_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void placeOrder_ignoresClientSuppliedId() throws Exception {
        when(restTemplate.postForObject(contains("/api/payments"), any(), eq(Object.class))).thenReturn(Map.of());
        Order existing = orderService.placeOrder(newOrder());
        Map<String, Object> body = Map.of("id", existing.getId(), "userId", 7, "productId", 1, "quantity", 9);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        assertEquals(1L, orderRepository.findById(existing.getId()).orElseThrow().getUserId());
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

    @Test
    void userServiceDown_returns503() throws Exception {
        when(restTemplate.getForObject(contains("/api/users/"), eq(Object.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newOrder())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("user-service is unavailable"));
    }

    @Test
    void productServiceError_returns503() throws Exception {
        when(restTemplate.getForObject(contains("/api/products/"), eq(ProductDTO.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newOrder())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("product-service is unavailable"));
    }

    @Test
    void totalPriceOverflow_returns400() throws Exception {
        when(restTemplate.getForObject(contains("/api/products/"), eq(ProductDTO.class)))
                .thenReturn(new ProductDTO(1L, "Widget", "A widget", Long.MAX_VALUE, 5, "Tools"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newOrder())))
                .andExpect(status().isBadRequest());
    }

    private Order storedOrder(OrderStatus status, LocalDateTime createdAt) {
        return Order.builder().userId(1L).productId(1L).quantity(1).totalPrice(100L)
                .status(status).createdAt(createdAt).build();
    }

    private Order newOrder() {
        return Order.builder().userId(1L).productId(1L).quantity(2).build();
    }

    private OrderStatus storedStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
