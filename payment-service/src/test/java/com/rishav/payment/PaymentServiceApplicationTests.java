package com.rishav.payment;

import com.rishav.payment.kafka.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PaymentServiceApplicationTests {

	// Test contexts share the in-memory database, so OutboxRelay can find rows other tests wrote and must not reach Kafka.
	@MockitoBean
	private PaymentEventPublisher paymentEventPublisher;

	@Test
	void contextLoads() {
	}

}
