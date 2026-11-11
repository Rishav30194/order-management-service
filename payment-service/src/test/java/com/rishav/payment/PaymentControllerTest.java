package com.rishav.payment;

import tools.jackson.databind.ObjectMapper;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import com.rishav.payment.repository.OutboxEventRepository;
import com.rishav.payment.repository.PaymentResponseRepository;
import com.rishav.payment.kafka.PaymentEventPublisher;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlZC1vdXRzaWRlLWNp",
        "jwt.expiration-ms=86400000",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "kafka.topic.name=payment-events",
        // Keeps OutboxRelay from deleting rows during a test, so tests can count the outbox events a payment wrote.
        "payment.outbox.relay-interval=PT1H"
})
@AutoConfigureMockMvc
class PaymentControllerTest {

    private static final String JWT_SECRET =
            "dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlZC1vdXRzaWRlLWNp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentResponseRepository paymentResponseRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private PaymentEventPublisher paymentEventPublisher;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
        String token = Jwts.builder()
                .subject("order-service")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(key)
                .compact();
        bearerToken = "Bearer " + token;
    }

    @Test
    void makePayment_happyPath() throws Exception {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("101")
                .customerId("user-1")
                .paymentMethod("MOCK")
                .amount(BigDecimal.valueOf(500))
                .build();

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("101"))
                .andExpect(jsonPath("$.status").isNotEmpty());
    }

    @Test
    void makePayment_withEndUserToken_returns403() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
        String userToken = Jwts.builder()
                .subject("alice@example.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(key)
                .compact();
        PaymentRequest request = PaymentRequest.builder()
                .orderId("103")
                .customerId("user-3")
                .paymentMethod("MOCK")
                .amount(BigDecimal.valueOf(100))
                .build();

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void makePayment_withoutToken_returns403() throws Exception {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("102")
                .customerId("user-2")
                .paymentMethod("MOCK")
                .amount(BigDecimal.valueOf(100))
                .build();

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void makePayment_sameOrderTwice_returnsOriginalPayment() throws Exception {
        PaymentResponseDto first = readPayment(postPayment("201", new BigDecimal("500")).andExpect(status().isOk()));
        PaymentResponseDto second = readPayment(postPayment("201", new BigDecimal("500.00")).andExpect(status().isOk()));

        assertEquals(first, second);
        assertEquals(1, outboxEventsFor("201"));
    }

    @Test
    void makePayment_sameOrderDifferentAmount_returns409() throws Exception {
        postPayment("202", new BigDecimal("100")).andExpect(status().isOk());

        postPayment("202", new BigDecimal("150")).andExpect(status().isConflict());

        assertEquals(0, new BigDecimal("100").compareTo(
                paymentResponseRepository.findByOrderId("202").orElseThrow().getAmount()));
        assertEquals(1, outboxEventsFor("202"));
    }

    @Test
    void makePayment_concurrentSameOrder_createsOnePayment() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PaymentResponseDto> first = executor.submit(() -> {
                start.await();
                return readPayment(postPayment("203", new BigDecimal("300")).andExpect(status().isOk()));
            });
            Future<PaymentResponseDto> second = executor.submit(() -> {
                start.await();
                return readPayment(postPayment("203", new BigDecimal("300")).andExpect(status().isOk()));
            });
            start.countDown();

            assertEquals(first.get(), second.get());
        }
        assertEquals(1, outboxEventsFor("203"));
    }

    @Test
    void makePayment_amountWithMoreThanTwoDecimals_returns400() throws Exception {
        postPayment("204", new BigDecimal("10.005")).andExpect(status().isBadRequest());
    }

    private ResultActions postPayment(String orderId, BigDecimal amount) throws Exception {
        PaymentRequest request = PaymentRequest.builder()
                .orderId(orderId)
                .customerId("user-" + orderId)
                .paymentMethod("MOCK")
                .amount(amount)
                .build();
        return mockMvc.perform(post("/api/payments")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private PaymentResponseDto readPayment(ResultActions result) throws Exception {
        return objectMapper.readValue(result.andReturn().getResponse().getContentAsString(), PaymentResponseDto.class);
    }

    private long outboxEventsFor(String orderId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> event.getPayload().contains("\"orderId\":\"" + orderId + "\""))
                .count();
    }
}
