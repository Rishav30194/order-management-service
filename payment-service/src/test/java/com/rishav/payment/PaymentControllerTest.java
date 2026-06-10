package com.rishav.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.kafka.PaymentEventPublisher;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlZC1vdXRzaWRlLWNp",
        "jwt.expiration-ms=86400000",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "kafka.topic.name=payment-events"
})
@AutoConfigureMockMvc
class PaymentControllerTest {

    private static final String JWT_SECRET =
            "dGVzdC1vbmx5LWp3dC1zZWNyZXQtbmV2ZXItdXNlZC1vdXRzaWRlLWNp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
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
}
